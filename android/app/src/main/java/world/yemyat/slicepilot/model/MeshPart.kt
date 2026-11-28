package world.yemyat.slicepilot.model

import world.yemyat.slicepilot.preview.ModelTransform
import kotlin.math.max
import kotlin.math.min

/**
 * One printable part inside a [MeshData].
 *
 * A parsed file used to collapse into a single anonymous blob, which is why a 3MF holding
 * two small parts could only be printed as one lump: the only transform available moved
 * the whole blob, so the parts could not be separated and nothing could be left behind.
 * A part is a *range* of the shared vertex/index buffers (not a copy of them): the
 * renderer draws each range with its own matrix, the slicer rebuilds one model object per
 * range, and the UI can address parts by name.
 */
data class MeshPart(
    val name: String,
    /** First vertex of this part in [MeshData.positions] / 3. */
    val vertexOffset: Int,
    val vertexCount: Int,
    /** First index of this part in [MeshData.indices] (indices, not bytes). */
    val indexOffset: Int,
    /** Number of indices, i.e. triangles * 3. */
    val indexCount: Int,
    /** Authored bounding box — where the file put the part, before any user pose. */
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    val halfX get() = max(0f, (maxX - minX) / 2f)
    val halfY get() = max(0f, (maxY - minY) / 2f)
    val halfZ get() = max(0f, (maxZ - minZ) / 2f)
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
    val width get() = maxX - minX
    val depth get() = maxY - minY
    val height get() = maxZ - minZ

    /** The pose that leaves this part where the file authored it, on a W×H plate. */
    fun poseAt(
        plateW: Float,
        plateH: Float,
    ): ModelTransform = ModelTransform(offsetX = centerX - plateW / 2f, offsetY = centerY - plateH / 2f)

    /** What the plate has to hold when the part sits in [pose]: width, depth, height (mm). */
    fun footprint(pose: ModelTransform): FloatArray = pose.extents(halfX, halfY, halfZ)

    /** This part's row for NativeLib.nativeSetPartPoses: the pose plus whether it is printed. */
    fun poseRow(
        pose: ModelTransform,
        print: Boolean,
    ): FloatArray = pose.toArray() + (if (print) 1f else 0f)

    companion object {
        /** Floats per part in a pose row (the eight ModelTransform values plus the print flag). */
        const val POSE_ROW_FLOATS = 9

        /**
         * Floats per part in the native parser's row table: the four range values (vertex
         * start, vertex count, index start, index count) followed by the authored bounding
         * box (minX, minY, minZ, maxX, maxY, maxZ), so one FloatArray carries the whole table
         * while the names travel in a parallel String array.
         */
        const val ROW_FLOATS = 10

        /**
         * Decodes a native row table (and its names) into parts; a partial row at the tail is
         * dropped rather than guessed at. An empty table means an older library, which leaves
         * [MeshData.parts] empty and the mesh treated as one anonymous piece.
         */
        fun fromRows(
            rows: FloatArray,
            names: List<String>,
        ): List<MeshPart> {
            val out = ArrayList<MeshPart>(rows.size / ROW_FLOATS)
            var i = 0
            while ((i + 1) * ROW_FLOATS <= rows.size) {
                val o = i * ROW_FLOATS
                out.add(
                    MeshPart(
                        name = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Part ${i + 1}",
                        vertexOffset = rows[o].toInt(),
                        vertexCount = rows[o + 1].toInt(),
                        indexOffset = rows[o + 2].toInt(),
                        indexCount = rows[o + 3].toInt(),
                        minX = rows[o + 4],
                        minY = rows[o + 5],
                        minZ = rows[o + 6],
                        maxX = rows[o + 7],
                        maxY = rows[o + 8],
                        maxZ = rows[o + 9],
                    ),
                )
                i++
            }
            return out
        }
    }
}

/** One row ("shelf") of a packed layout: which parts sit in it, and how big it is. */
internal class PackRow(
    val partIndices: List<Int>,
    /** X of each entry's cell, measured from the left edge of the row. */
    val cellLeft: List<Float>,
    val width: Float,
    val depth: Float,
)

/**
 * Where the parts of a model sit on a plate, and whether they will fit there.
 *
 * Three rules keep the answer honest instead of "optimistic":
 * - a single part stays centred on the plate (the behaviour every release before parts
 *   existed had, and what an STL authored around the origin needs),
 * - several parts keep the layout the file authored whenever that layout fits, and
 * - only when it does not fit do they get packed tighter — the "two small models on one
 *   3MF are too wide for the plate" complaint.
 */
object PartPlacement {
    /** Gap kept between parts when packing, in mm. */
    const val PACK_MARGIN_MM = 3f

    /**
     * The flat payload [world.yemyat.slicepilot.NativeLib.nativeSetPartPoses] wants for
     * [poses] and [included], in part order, or null when [mesh] has no pieces of its own
     * (a plain STL, or a decimated copy) so the caller keeps using the single transform.
     */
    fun poseRows(
        mesh: MeshData,
        poses: List<ModelTransform>,
        skipped: Set<Int> = emptySet(),
    ): FloatArray? {
        val parts = mesh.partsForRendering
        if (parts.size < 2 || mesh.parts.isEmpty()) return null
        val rows = FloatArray(parts.size * MeshPart.POSE_ROW_FLOATS)
        for (i in parts.indices) {
            val pose = poses.getOrNull(i) ?: ModelTransform()
            System.arraycopy(
                parts[i].poseRow(pose, print = i !in skipped),
                0,
                rows,
                i * MeshPart.POSE_ROW_FLOATS,
                MeshPart.POSE_ROW_FLOATS,
            )
        }
        return rows
    }

    /** The pose that centres a part on the plate — the pre-parts behaviour. */
    val centeredPose: ModelTransform
        get() = ModelTransform()

    /**
     * Default poses for every part of [mesh] on a [plateW]×[plateH] plate: the authored
     * layout when it fits, otherwise the tightest shelf packing that does.
     */
    fun defaultPoses(
        mesh: MeshData,
        plateW: Float,
        plateH: Float,
    ): List<ModelTransform> {
        val parts = mesh.partsForRendering
        val single = parts.size < 2
        val authored = if (single) listOf(centeredPose) else parts.map { it.poseAt(plateW, plateH) }
        if (single) return authored
        val (w, d) = unionFootprint(parts, authored)
        if (w <= plateW && d <= plateH) return authored
        return packedPoses(parts, plateW, plateH) ?: authored
    }

    /**
     * Pack [parts] into rows across the plate, widest first, and return one pose per part
     * in the original part order, or null when even the tightest packing is bigger than
     * the plate.
     */
    fun packedPoses(
        parts: List<MeshPart>,
        plateW: Float,
        plateH: Float,
        margin: Float = PACK_MARGIN_MM,
    ): List<ModelTransform>? {
        if (parts.isEmpty()) return emptyList()
        val order =
            parts.indices.sortedWith(
                compareByDescending<Int> { parts[it].width }.thenByDescending { parts[it].depth },
            )
        val cellW = order.map { parts[it].width + margin }
        val cellD = order.map { parts[it].depth + margin }
        if (cellW.max() > plateW || cellD.max() > plateH) return null

        val rows = ArrayList<PackRow>()
        var rowParts = ArrayList<Int>()
        var rowLeft = ArrayList<Float>()
        var rowW = 0f
        for ((slot, partIndex) in order.withIndex()) {
            if (rowParts.isNotEmpty() && rowW + cellW[slot] > plateW) {
                rows.add(PackRow(rowParts, rowLeft, rowW, rowLeft.indices.maxOf { cellD[it] }))
                rowParts = ArrayList()
                rowLeft = ArrayList()
                rowW = 0f
            }
            rowParts.add(partIndex)
            rowLeft.add(rowW)
            rowW += cellW[slot]
        }
        if (rowParts.isNotEmpty()) {
            rows.add(PackRow(rowParts, rowLeft, rowW, rowLeft.indices.maxOf { cellD[it] }))
        }

        val blockW = rows.maxOf { it.width }
        val blockD = rows.sumOf { it.depth.toDouble() }.toFloat()
        if (blockW > plateW || blockD > plateH) return null

        // A pose places the part's centre at (plate centre + offset), so a target centre
        // is expressed as offset = target − plate centre; the padding centres the block.
        val poses = Array<ModelTransform?>(parts.size) { null }
        for ((rowIndex, row) in rows.withIndex()) {
            val padX = (plateW - blockW) / 2f
            val padY = (plateH - blockD) / 2f + row.depth * rowIndex
            for ((slot, partIndex) in row.partIndices.withIndex()) {
                val part = parts[partIndex]
                poses[partIndex] =
                    ModelTransform(
                        offsetX = padX + row.cellLeft[slot] + part.width / 2f - plateW / 2f,
                        offsetY = padY + row.depth / 2f - plateH / 2f,
                    )
            }
        }
        return if (poses.any { it == null }) null else poses.mapNotNull { it }
    }

    /** Width and depth (mm) the plate has to hold for these poses. */
    fun unionFootprint(
        parts: List<MeshPart>,
        poses: List<ModelTransform>,
        included: List<Boolean> = emptyList(),
    ): Pair<Float, Float> {
        if (parts.isEmpty()) return 0f to 0f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var seen = false
        for (i in parts.indices) {
            if (included.isNotEmpty() && !included[i]) continue
            val pose = poses.getOrElse(i) { ModelTransform() }
            val ext = parts[i].footprint(pose)
            // A pose says where the part's centre sits relative to the plate centre, so
            // the plate centre cancels out of a relative footprint and offsets are enough.
            val x = pose.offsetX
            val y = pose.offsetY
            minX = min(minX, x - ext[0])
            maxX = max(maxX, x + ext[0])
            minY = min(minY, y - ext[1])
            maxY = max(maxY, y + ext[1])
            seen = true
        }
        if (!seen) return 0f to 0f
        return (maxX - minX) to (maxY - minY)
    }

    /**
     * Full height (mm) the printed pieces reach in [poses]. [MeshPart.footprint] reports the
     * half-height (the same value doubles to a piece's real height), so the answer is twice the
     * tallest printed piece - or 0 when every piece is skipped, which is a real state the UI
     * offers and would make maxOf throw. One copy of that maths now serves the setup sheet's
     * height readout, the plate check below and the slicing view's reveal band.
     */
    fun printedHeightMm(
        parts: List<MeshPart>,
        poses: List<ModelTransform>,
        skipped: Set<Int> = emptySet(),
    ): Float {
        val printed = parts.indices.filter { it !in skipped }
        return printed
            .maxOfOrNull { parts[it].footprint(poses.getOrElse(it) { ModelTransform() })[2] }
            ?.times(2f)
            ?: 0f
    }

    /** True when every included part lies inside the plate and under the plate height. */
    fun fits(
        mesh: MeshData,
        poses: List<ModelTransform>,
        included: List<Boolean>,
        plateW: Float,
        plateH: Float,
        plateZ: Float,
    ): Boolean {
        val parts = mesh.partsForRendering
        if (parts.isEmpty()) return false
        val (w, d) = unionFootprint(parts, poses, included)
        val skipped = parts.indices.filter { included.isNotEmpty() && !included[it] }.toSet()
        return w <= plateW && d <= plateH && printedHeightMm(parts, poses, skipped) <= plateZ
    }
}
