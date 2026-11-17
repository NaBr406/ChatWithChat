package cn.nabr.chatwithchat.data.generation

/** Called while accepting a user-initiated turn, before dispatching its network work. */
fun interface ChatGenerationKeepAlive {
    fun start()
}
