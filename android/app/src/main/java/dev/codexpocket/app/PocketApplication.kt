package dev.codexpocket.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.codexpocket.app.data.PocketRepository
import dev.codexpocket.app.data.ExecutionLocations

class PocketApplication : Application(), DefaultLifecycleObserver {
    internal val locations by lazy { ExecutionLocations(this) }
    val repository get() = checkNotNull(locations.active.value) { "Execution locations are initializing" }
    override fun onCreate() { super<Application>.onCreate(); ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) = locations.setForeground(true)
    override fun onStop(owner: LifecycleOwner) = locations.setForeground(false)
}
