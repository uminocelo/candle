package com.uminocelo.candle.player

/** Commands for the open video player, from any thread (the DLNA renderer runs on the server's). */
interface VideoCommands {
    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Closes the player, back to whatever was on screen before. */
    fun close()
}

/** The video player on screen, if any. A newer player replaces an older one that is still closing. */
object ActiveVideo {
    @Volatile
    var commands: VideoCommands? = null
        private set

    fun open(commands: VideoCommands) {
        this.commands = commands
    }

    /** Forgets [commands] if they are still the active ones; false when a newer player took over. */
    @Synchronized
    fun release(commands: VideoCommands): Boolean {
        if (this.commands !== commands) return false
        this.commands = null
        return true
    }

    fun isActive(commands: VideoCommands): Boolean = this.commands === commands
}
