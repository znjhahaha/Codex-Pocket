package dev.codexpocket.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.codexpocket.app.data.PocketRepository

class PocketApplication : Application(), DefaultLifecycleObserver {
    val repository by lazy { PocketRepository(this) }
    override fun onCreate() { super<Application>.onCreate(); ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) = repository.setForeground(true)
    override fun onStop(owner: LifecycleOwner) = repository.setForeground(false)
}
