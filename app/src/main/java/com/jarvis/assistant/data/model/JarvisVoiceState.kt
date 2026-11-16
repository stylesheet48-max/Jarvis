package com.jarvis.assistant.data.model

/**
 * Authoritative central state machine governing voice capture,
 * offline wake-word standby, sleep mode, and active conversation.
 */
enum class JarvisVoiceState {
    /**
     * Complete rest: conversational pipeline stopped, AudioPlayer flushed,
     * no audio streamed to Gemini, zero external API requests.
     */
    SLEEP,

    /**
     * Offline local wake-word listener active (Vosk engine).
     * No audio streamed to cloud; audio frames evaluated purely in local memory.
     * Listens for configured wake words: "Hey JARVIS" and "Hey Myra".
     */
    STANDBY_WAKE_WORD,

    /**
     * Active real-time bidirectional conversation with Google Gemini Live API.
     */
    ACTIVE_CONVERSATION,

    /**
     * Stopped due to missing permissions, invalid API key, or hardware errors.
     */
    ERROR_OR_PERMISSION_REQUIRED
}
