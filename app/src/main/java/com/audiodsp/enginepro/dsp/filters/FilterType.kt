package com.audiodsp.enginepro.dsp.filters

/**
 * Standard Second-Order Biquad Filter types based on Robert Bristow-Johnson (RBJ)
 * Audio EQ Cookbook equations.
 */
enum class FilterType {
    PEAKING,
    LOW_SHELF,
    HIGH_SHELF,
    LOW_PASS,
    HIGH_PASS,
    BAND_PASS,
    ALL_PASS,
    NOTCH
}
