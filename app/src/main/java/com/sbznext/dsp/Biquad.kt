package com.sbznext.dsp

import kotlin.math.*

class Biquad(private val sampleRate: Float) {
    private var b0=1f; private var b1=0f; private var b2=0f; private var a1=0f; private var a2=0f
    private var z1L=0f; private var z2L=0f; private var z1R=0f; private var z2R=0f

    fun reset() { z1L=0f; z2L=0f; z1R=0f; z2R=0f }
    private fun set(b0n:Double,b1n:Double,b2n:Double,a0n:Double,a1n:Double,a2n:Double) {
        b0=(b0n/a0n).toFloat(); b1=(b1n/a0n).toFloat(); b2=(b2n/a0n).toFloat()
        a1=(a1n/a0n).toFloat(); a2=(a2n/a0n).toFloat()
    }
    fun peaking(freq:Float,q:Float,gainDb:Float) {
        val A=10.0.pow(gainDb/40.0); val w=2.0*PI*freq/sampleRate; val c=cos(w); val s=sin(w); val alpha=s/(2*q); val a0=1+alpha/A
        set(1+alpha*A,-2*c,1-alpha*A,a0,-2*c,1-alpha/A)
    }
    fun lowShelf(freq:Float,slope:Float,gainDb:Float) {
        val A=10.0.pow(gainDb/40.0); val w=2*PI*freq/sampleRate; val c=cos(w); val s=sin(w); val alpha=s/2*sqrt((A+1/A)*(1/slope-1)+2); val beta=2*sqrt(A)*alpha
        set(A*((A+1)-(A-1)*c+beta),2*A*((A-1)-(A+1)*c),A*((A+1)-(A-1)*c-beta),(A+1)+(A-1)*c+beta,-2*((A-1)+(A+1)*c),(A+1)+(A-1)*c-beta)
    }
    fun highShelf(freq:Float,slope:Float,gainDb:Float) {
        val A=10.0.pow(gainDb/40.0); val w=2*PI*freq/sampleRate; val c=cos(w); val s=sin(w); val alpha=s/2*sqrt((A+1/A)*(1/slope-1)+2); val beta=2*sqrt(A)*alpha
        set(A*((A+1)+(A-1)*c+beta),-2*A*((A-1)+(A+1)*c),A*((A+1)+(A-1)*c-beta),(A+1)-(A-1)*c+beta,2*((A-1)-(A+1)*c),(A+1)-(A-1)*c-beta)
    }
    fun lowPass(freq:Float,q:Float=0.7071f) { val w=2*PI*freq/sampleRate; val c=cos(w); val s=sin(w); val a=s/(2*q); set((1-c)/2,1-c,(1-c)/2,1+a,-2*c,1-a) }
    fun highPass(freq:Float,q:Float=0.7071f) { val w=2*PI*freq/sampleRate; val c=cos(w); val s=sin(w); val a=s/(2*q); set((1+c)/2,-(1+c),(1+c)/2,1+a,-2*c,1-a) }
    fun processL(x:Float):Float { val y=b0*x+z1L; z1L=b1*x-a1*y+z2L; z2L=b2*x-a2*y; return y }
    fun processR(x:Float):Float { val y=b0*x+z1R; z1R=b1*x-a1*y+z2R; z2R=b2*x-a2*y; return y }
}
