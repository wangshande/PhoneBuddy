package com.deskbuddy

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 给没有 Activity 的场景（前台服务）提供 CameraX 所需的 LifecycleOwner。
 * 进程内单例，由 DeskService 的生命周期统一启停。
 */
object ServiceLifecycle : LifecycleOwner {

    private var registry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    fun owner(): LifecycleOwner {
        if (registry.currentState == Lifecycle.State.DESTROYED) registry = LifecycleRegistry(this)
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.STARTED
        }
        return this
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
