package com.uminocelo.candle.home

/** An activity by package and class name; a plain-Kotlin stand-in for `ComponentName`. */
data class ComponentRef(val packageName: String, val className: String)

/** An app that shows up in a TV launcher. */
data class LaunchableApp(val component: ComponentRef, val label: String)
