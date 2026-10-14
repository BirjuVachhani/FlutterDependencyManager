package dev.birju.flutterdependencymanager

/**
 * Minimal pub semver: `major.minor.patch[-prerelease][+build]`.
 * Build metadata is ignored for ordering, prerelease sorts below its release,
 * prerelease identifiers compare numerically when both are numeric.
 */
class PubVersion private constructor(
    private val nums: IntArray,
    private val pre: List<String>,
) : Comparable<PubVersion> {

    override fun compareTo(other: PubVersion): Int {
        for (i in 0..2) {
            val c = nums[i].compareTo(other.nums[i])
            if (c != 0) return c
        }
        // No prerelease outranks any prerelease of the same x.y.z
        if (pre.isEmpty() || other.pre.isEmpty()) {
            return other.pre.size.compareTo(pre.size)
        }
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers sort below alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val SHAPE = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?""")

        fun parse(text: String): PubVersion? {
            val m = SHAPE.matchEntire(text.trim()) ?: return null
            val nums = IntArray(3) { m.groupValues[it + 1].toIntOrNull() ?: return null }
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return PubVersion(nums, pre)
        }

        /** True when [latest] is strictly newer than the version inside [constraint] (`1.2.3` or `^1.2.3`). */
        fun isOutdated(constraint: String, latest: String): Boolean = updateType(constraint, latest) != null

        /**
         * How big a step [latest] is from the version inside [constraint], or null when it is not newer.
         * Below 1.0.0 pub reads versions as `0.MAJOR.MINOR+PATCH`, which is what `^` follows (`^0.2.0`
         * means `<0.3.0`, `^0.0.3` means `<0.1.0`): `0.2.0 -> 0.3.0` is major, `0.2.0 -> 0.2.1` is minor.
         */
        fun updateType(constraint: String, latest: String): UpdateType? {
            val cur = parse(constraint.removePrefix("^")) ?: return null
            val new = parse(latest) ?: return null
            if (new <= cur) return null
            val changed = (0..2).firstOrNull { new.nums[it] != cur.nums[it] }
                ?: return UpdateType.PATCH // same x.y.z: a newer prerelease, or the release itself
            val belowOne = cur.nums[0] == 0
            return when {
                changed == 0 || belowOne && changed == 1 -> UpdateType.MAJOR
                changed == 1 || belowOne -> UpdateType.MINOR
                else -> UpdateType.PATCH
            }
        }
    }
}

/** Kind of update by pub's semver convention; see [PubVersion.updateType]. */
enum class UpdateType(val label: String) { MAJOR("Major"), MINOR("Minor"), PATCH("Patch") }
