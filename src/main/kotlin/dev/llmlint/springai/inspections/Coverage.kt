package dev.llmlint.springai.inspections

/**
 * What the analysis can prove about a setting (an output limit) for one call.
 *
 * - [SAFE]: every path the analysis can see sets it.
 * - [UNSAFE]: no path sets it.
 * - [MIXED]: some paths set it and at least one path definitely does not.
 * - [UNKNOWN]: a value comes from code the analysis cannot see (a parameter, a library bean, a placeholder).
 */
enum class Coverage {
    SAFE, UNSAFE, UNKNOWN, MIXED;

    /**
     * Alternatives: the value is one of the two (if/else, ternary, several returns, several assignments).
     * A safe and an unsafe alternative make [MIXED]; one definitely unsafe alternative next to an unknown
     * one is [MIXED] too, because that unsafe path exists.
     */
    infix fun join(other: Coverage): Coverage = when {
        this == other -> this
        this == MIXED || other == MIXED -> MIXED
        UNSAFE == this || UNSAFE == other -> MIXED // SAFE+UNSAFE, UNKNOWN+UNSAFE
        else -> UNKNOWN // SAFE+UNKNOWN
    }

    /**
     * Layers that apply together on the same path (request options over model options): one layer that
     * sets the value is enough. [UNSAFE] (sets nothing) is the neutral element.
     */
    infix fun layer(other: Coverage): Coverage = when {
        this == SAFE || other == SAFE -> SAFE
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        this == MIXED || other == MIXED -> MIXED
        else -> UNSAFE
    }
}
