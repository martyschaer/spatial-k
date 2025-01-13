@file:OptIn(ExperimentalTurfApi::class) @file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth")

package io.github.dellisd.spatialk.turf

import io.github.dellisd.spatialk.geojson.*
import kotlin.math.sign

public fun polygonize(geoJson: MultiLineString): FeatureCollection {
    val graph = Graph.fromGeoJson(geoJson)

    // 1. Remove dangel node
    graph.deleteDangles()

    // 2. Remove cut-edges (bridge edges)
    graph.deleteCutEdges()

    // 3. Get all holes and shells
    val holes = mutableListOf<EdgeRing>()
    val shells = mutableListOf<EdgeRing>()

    graph.getEdgeRings().filter { it.isValid() }.forEach { edgeRing ->
        if (edgeRing.isHole()) {
            // holes are "in reverse order"
            holes.add(edgeRing)
        } else {
            shells.add(edgeRing)
        }
    }

    // 4. Assign Holes to Shells
    holes.forEach { hole ->
        if (EdgeRing.findEdgeRingContaining(hole, shells) != null) {
            shells.add(hole)
        }
    }

    return FeatureCollection(holes.map { Feature(it.toPolygon()) })
}

private class Graph(
    private val nodes: MutableMap<String, Node> = mutableMapOf<String, Node>(),
    private val edges: MutableList<Edge> = ArrayList(),
) {
    companion object {
        fun fromGeoJson(geoJson: MultiLineString): Graph {
            val graph = Graph()

            geoJson.coordinates.forEach { lineStringCoords ->
                lineStringCoords.map { it.coordinates.toList() }.windowed(2).forEach { (prev, cur) ->
                    val start = graph.getNode(prev)
                    val end = graph.getNode(cur)
                    if (start !== end) {
                        graph.addEdge(start, end)
                    }
                }
            }

            return graph
        }
    }

    private fun getNode(coordinates: List<Double>): Node {
        return nodes.getOrPut(Node.buildId(coordinates)) { Node(coordinates) }
    }

    private fun addEdge(from: Node, to: Node) {
        val edge = Edge(from = from, to = to)
        edges.add(edge)
        edges.add(edge.getSymmetricEdge())
    }

    fun deleteDangles() {
        nodes.keys.map { nodes[it]!! }.forEach { removeIfDangle(it) }
    }

    private fun removeIfDangle(node: Node) {
        if (node.getInnerEdges().size <= 1) {
            val outerNodes = node.getOuterEdges().map { it.to }
            removeNode(node)
            outerNodes.forEach { removeIfDangle(it) }
        }
    }

    private fun removeNode(node: Node) {
        node.getOuterEdges().forEach { removeEdge(it) }
        node.getInnerEdges().forEach { removeEdge(it) }
        nodes.remove(node.id)
    }

    private fun removeEdge(edge: Edge) {
        edges.remove(edge)
        edge.deleteEdge()
    }

    fun deleteCutEdges() {
        computeNextCWEdges()
        findLabeledEdgeRings()

        edges.removeAll { edge ->
            edge.label == edge.getSymmetricEdge().label
        }
    }

    private fun computeNextCWEdges(node: Node? = null) {
        if (node == null) {
            nodes.keys.forEach { id ->
                computeNextCWEdges(nodes[id])
            }
        } else {
            node.getOuterEdges().forEachIndexed { i, edge ->
                node.getOuterEdge(
                    (if (i == 0) node.getOuterEdges().size else i) - 1
                ).getSymmetricEdge().next = edge
            }
        }
    }

    private fun computeNextCCWEdges(node: Node, label: Int) {
        val edges = node.getOuterEdges()
        var firstOutDE: Edge? = null
        var prevInDE: Edge? = null

        for (i in edges.size - 1 downTo 0) {
            val de = edges[i]
            val sym = de.getSymmetricEdge()
            var outDE: Edge? = null
            var inDE: Edge? = null

            if (de.label == label) {
                outDE = de
            }

            if (sym.label == label) {
                inDE = sym
            }

            if (outDE == null || inDE == null) {
                // This edge is not in edgering
                continue
            }

            prevInDE = inDE
            prevInDE.next = outDE
            prevInDE = null

            if (firstOutDE == null) {
                firstOutDE = outDE
            }
        }

        if (prevInDE != null) {
            prevInDE.next = firstOutDE
        }
    }

    private fun findLabeledEdgeRings(): List<Edge> {
        val edgeRingStarts = mutableListOf<Edge>()
        var label = 0

        for (edge in edges) {
            if (edge.label != null && edge.label!! >= 0) {
                continue
            }
            edgeRingStarts.add(edge)
            var e = edge
            do {
                e.label = label
                e = e.next!!
            } while (edge != e)
            label++
        }

        return edgeRingStarts
    }

    fun getEdgeRings(): List<EdgeRing> {
        computeNextCWEdges()

        // Clear labels
        this.edges.forEach { edge ->
            edge.label = null
        }

        findLabeledEdgeRings().forEach { edge ->
            findIntersectionNodes(edge).forEach { node ->
                computeNextCCWEdges(node, edge.label!!)
            }
        }

        val edgeRingList = mutableListOf<EdgeRing>()

        edges.forEach { edge ->
            if (edge.ring == null) {
                edgeRingList.add(findEdgeRing(edge))
            }
        }

        return edgeRingList
    }

    private fun findEdgeRing(startEdge: Edge): EdgeRing {
        var edge = startEdge
        val edgeRing = EdgeRing()

        do {
            edgeRing.push(edge)
            edge.ring = edgeRing
            edge = edge.next!!
        } while (startEdge != edge)

        return edgeRing
    }

    private fun findIntersectionNodes(startEdge: Edge): List<Node> {
        val intersectionNodes = mutableListOf<Node>()
        var edge = startEdge
        do {
            var degree = 0
            edge.from.getOuterEdges().forEach { e ->
                if (e.label == startEdge.label) {
                    degree++
                }
            }
            if (degree > 1) {
                intersectionNodes.add(edge.from)
            }
            edge = edge.next!!
        } while (startEdge != edge)
        return intersectionNodes
    }
}

private class Edge(
    var label: Int? = null,
    private var symmetric: Edge? = null,
    var from: Node,
    var to: Node,
    var next: Edge? = null,
    var ring: EdgeRing? = null,
) {
    init {
        this.from.addOuterEdge(this)
        this.to.addInnerEdge(this)
    }

    fun getSymmetricEdge(): Edge {
        if (symmetric == null) {
            this.symmetric = Edge(from = to, to = from, symmetric = this)
        }
        return this.symmetric!!
    }

    fun deleteEdge() {
        from.removeOuterEdge(this)
        to.removeInnerEdge(this)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Edge) return false
        return this.from.id == other.from.id && this.to.id == other.to.id
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + from.id.hashCode()
        result = 31 * result + to.id.hashCode()
        return result
    }
}

private class EdgeRing(
    private var edges: MutableList<Edge> = mutableListOf<Edge>(),
    private var polygon: Polygon? = null,
    private var envelope: Polygon? = null,
) {

    companion object {
        fun findEdgeRingContaining(testEdgeRing: EdgeRing, shellList: List<EdgeRing>): EdgeRing? {
            val testEnvelope = testEdgeRing.getEnvelope()

            var minEnvelope: Polygon? = null
            var minShell: EdgeRing? = null

            for (shell in shellList) {
                val tryEnvelope = shell.getEnvelope()
                if (minShell != null) {
                    minEnvelope = minShell.getEnvelope()
                }

                if (envelopeIsEqual(tryEnvelope, testEnvelope)) {
                    // the hole envelope cannot equal the shell envelope
                    continue
                }

                if (envelopeContains(tryEnvelope, testEnvelope)) {
                    val testEdgeRingCoordinates = testEdgeRing.map { edge, _, _ -> edge.from.coordinates }
                    var testPoint: Position? = null
                    for (pt in testEdgeRingCoordinates) {
                        if (!shell.some { edge, _, _ -> coordinatesEqual(pt, edge.from.coordinates) }) {
                            testPoint = Position(pt.toDoubleArray())
                        }
                    }

                    if (testPoint != null && shell.inside(Point(testPoint))) {
                        if (minShell == null || minEnvelope != null && envelopeContains(minEnvelope, tryEnvelope)) {
                            minShell = shell
                        }
                    }
                }
            }

            return minShell
        }
    }

    private fun inside(point: Point): Boolean {
        return booleanPointInPolygon(point, this.toPolygon())
    }

    fun push(edge: Edge) {
        edges.add(edge)
        this.envelope = null
        this.polygon = null
    }

    fun length(): Int {
        return edges.size
    }

    fun <T> map(fn: (edge: Edge, index: Int, array: List<Edge>) -> T): List<T> {
        return edges.mapIndexed { idx, it -> fn(it, idx, edges) }
    }

    fun some(fn: (edge: Edge, index: Int, array: List<Edge>) -> Boolean): Boolean {
        edges.forEachIndexed { idx, it ->
            if (fn(it, idx, edges)) {
                return true
            }
        }
        return false
    }

    fun isHole(): Boolean {
        val hiIndex = edges.foldIndexed(0) { i: Int, high: Int, edge: Edge ->
            if (edge.from.coordinates[1] > edges[high].from.coordinates[1]) {
                i
            } else {
                high
            }
        }
        val iPrev = (if (hiIndex == 0) length() else hiIndex) - 1
        val iNext = (hiIndex + 1) % length()
        val disc = orientationIndex(
            edges[iPrev].from.coordinates, edges[hiIndex].from.coordinates, edges[iNext].from.coordinates
        )

        if (disc == 0) {
            return edges[iPrev].from.coordinates[0] > edges[iNext].from.coordinates[0]
        }
        return disc > 0
    }

    fun toMultiPoint(): MultiPoint {
        return MultiPoint(edges.map { edge -> Position(edge.from.coordinates[0], edge.from.coordinates[1]) })
    }

    private fun getEnvelope(): Polygon {
        envelope?.let { return it }

        val envelope = envelope(this.toPolygon())
        this.envelope = envelope
        return envelope
    }

    fun toPolygon(): Polygon {
        polygon?.let { return it }

        var coordinates = edges.map { edge -> Position(edge.from.coordinates[0], edge.from.coordinates[1]) }
        coordinates = coordinates + coordinates.first() // close ring
        val polygon = Polygon(coordinates)
        this.polygon = polygon
        return polygon
    }


    /**
     * Check if the ring is valid in geometry terms.
     *
     * A ring must have either 0 or 4 or more points. The first and the last must be
     * equal (in 2D)
     * geos::geom::LinearRing::validateConstruction
     *
     * @return Validity of the EdgeRing
     */
    fun isValid(): Boolean {
        // stub (turfjs assumes rings are valid, so we do too)
        return true
    }
}

private class Node(
    val id: String,
    val coordinates: List<Double>,
    private var innerEdges: MutableList<Edge>,
    private var outerEdges: MutableList<Edge>,
    private var outerEdgesSorted: Boolean,
) {
    constructor(coordinates: List<Double>) : this(
        id = buildId(coordinates),
        coordinates = coordinates,
        innerEdges = mutableListOf(),
        outerEdges = mutableListOf(),
        outerEdgesSorted = false
    )

    companion object {
        fun buildId(coordinates: List<Double>): String {
            return coordinates.joinToString(",")
        }
    }

    fun addInnerEdge(edge: Edge) {
        this.innerEdges.add(edge)
    }

    fun addOuterEdge(edge: Edge) {
        this.outerEdges.add(edge)
        this.outerEdgesSorted = false
    }

    fun getInnerEdges(): List<Edge> {
        return innerEdges
    }

    fun getOuterEdges(): List<Edge> {
        sortOuterEdges()
        return outerEdges
    }

    fun getOuterEdge(i: Int): Edge {
        sortOuterEdges()
        return outerEdges[i]
    }

    private fun sortOuterEdges() {
        if (outerEdgesSorted) {
            return
        }
        outerEdges = outerEdges.sortedWith { a, b ->
            val aNode = a.to
            val bNode = b.to

            if (aNode.coordinates[0] - this.coordinates[0] >= 0 && bNode.coordinates[0] - this.coordinates[0] < 0) {
                return@sortedWith 1
            }
            if (aNode.coordinates[0] - this.coordinates[0] < 0 && bNode.coordinates[0] - this.coordinates[0] >= 0) {
                return@sortedWith -1
            }

            if (aNode.coordinates[0] == this.coordinates[0] && bNode.coordinates[0] == this.coordinates[0]) {
                if (aNode.coordinates[1] - this.coordinates[1] >= 0 || bNode.coordinates[1] - this.coordinates[1] >= 0) {
                    return@sortedWith mathSign(aNode.coordinates[1] - bNode.coordinates[1])
                }
                return@sortedWith mathSign(bNode.coordinates[1] - aNode.coordinates[1])
            }

            val det = orientationIndex(
                this.coordinates,
                aNode.coordinates,
                bNode.coordinates,
            )
            if (det < 0) return@sortedWith 1
            if (det > 0) return@sortedWith -1

            val d1 = pow2(aNode.coordinates[0] - this.coordinates[0]) + pow2(aNode.coordinates[1] - this.coordinates[1])
            val d2 = pow2(bNode.coordinates[0] - this.coordinates[0]) + pow2(bNode.coordinates[1] - this.coordinates[1])

            return@sortedWith mathSign(d1 - d2)
        }.toMutableList()
        outerEdgesSorted = true
    }

    fun removeInnerEdge(edge: Edge) {
        this.innerEdges.removeAll { it.from.id == edge.from.id }
    }

    fun removeOuterEdge(edge: Edge) {
        this.innerEdges.removeAll { it.to.id == edge.to.id }
    }
}

private fun pow2(x: Double): Double = x * x

private fun orientationIndex(p1: List<Double>, p2: List<Double>, q: List<Double>): Int {
    val dx1 = p2[0] - p1[0]
    val dy1 = p2[1] - p1[1]
    val dx2 = q[0] - p2[0]
    val dy2 = q[1] - p2[1]

    return mathSign(dx1 * dy2 - dx2 * dy1)
}

private fun mathSign(x: Double): Int {
    return sign(x).toInt()
}

private fun envelopeIsEqual(env1: Polygon, env2: Polygon): Boolean {
    val envX1 = env1.coordinates[0].map { it.coordinates[0] }
    val envY1 = env1.coordinates[0].map { it.coordinates[1] }
    val envX2 = env2.coordinates[0].map { it.coordinates[0] }
    val envY2 = env2.coordinates[0].map { it.coordinates[1] }

    return (envX1.maxOrNull() == envX2.maxOrNull() && //
            envY1.maxOrNull() == envY2.maxOrNull() && //
            envX1.minOrNull() == envX2.minOrNull() && //
            envY1.minOrNull() == envY2.minOrNull())
}

private fun envelopeContains(self: Polygon, env: Polygon): Boolean {
    return env.coordinates[0].all { pos -> booleanPointInPolygon(Point(pos), self) }
}

private fun coordinatesEqual(coord1: List<Double>, coord2: List<Double>): Boolean {
    return coord1[0] == coord2[0] && coord1[1] == coord2[1]
}