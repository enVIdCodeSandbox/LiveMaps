package dev.etran.livemaps.render

import dev.etran.livemaps.model.ViewSpec

/** Chooses the right renderer for a view and exposes change detection. */
object ViewRenderer {

    private fun geometry(ctx: RenderContext, v: ViewSpec) =
        ctx.source.geometry(v.source.target) ?: throw IllegalStateException("Map source '${v.source.target}' is not available (not loaded, or no tiles rendered yet)")

    /** Sources without height data (squaremap) can only be viewed top-down. */
    private fun useFlat(v: ViewSpec, hasHeight: Boolean) = v.isFlat || !hasHeight

    fun render(ctx: RenderContext, v: ViewSpec): RenderResult {
        val geo = geometry(ctx, v)
        return if (useFlat(v, geo.hasHeight)) FlatRenderer.render(ctx, v, geo) else RayRenderer.render(ctx, v, geo)
    }

    /** A token that changes whenever any source tile that influences this view changes. */
    fun fingerprint(ctx: RenderContext, v: ViewSpec): Long {
        val geo = ctx.source.geometry(v.source.target) ?: return Long.MIN_VALUE
        val plans = if (useFlat(v, geo.hasHeight)) listOf(FlatRenderer.plan(v, geo)) else RayRenderer.rings(ctx, v, geo).also {
            if (RayRenderer.totalTiles(it) > ctx.settings.maxTilesPerRender) return -1
        }.map { it.plan }
        var h = 17L
        for (p in plans) {
            val f = TileSet.fingerprint(ctx, v.source.target, p)
            if (f == -1L) return -1
            h = h * 31 + f
        }
        return h
    }

    /** Human readable list of the detail levels a 3D view uses, e.g. "1 block cells to 640 blocks, 5 to 1536". Null for flat views. */
    fun describeDetail(ctx: RenderContext, v: ViewSpec): String? {
        val geo = ctx.source.geometry(v.source.target) ?: return null
        if (useFlat(v, geo.hasHeight)) return null
        return RayRenderer.rings(ctx, v, geo).joinToString(", ") { r ->
            val b = r.level.blocksPerCell
            "${b}-block cells out to ${r.outer.toInt()}"
        }
    }
}
