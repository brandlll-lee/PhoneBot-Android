package dev.phonebot.android.engine

internal object NodeRuntime {
    init { System.loadLibrary("phonebot") }
    external fun start(script: String, home: String, temp: String, input: Int, output: Int): Int
}
