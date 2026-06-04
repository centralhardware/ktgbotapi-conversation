package me.centralhardware.telegram.conversation

import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.createSubContextAndDoAsynchronouslyWithUpdatesFilter
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * Wait-based conversation DSL for ktgbotapi bots.
 *
 * Each `wait*` helper sends a prompt, suspends until the user replies in the same chat, and
 * loops on invalid input until it gets something usable. Sending [CANCEL] at any point aborts
 * the whole flow with [ConversationCancelledException] — wrap the conversation body in a
 * `try/catch` to localise the "cancelled" message.
 *
 * The engine is intentionally free of any domain types: prompts and error strings are passed
 * in by the caller, so localisation and validation stay on the caller's side.
 */

/** Sentinel words the user can send to drive a conversation. */
const val SKIP = "/skip"
const val COMPLETE = "/complete"
const val CANCEL = "/cancel"

/** Thrown by any `wait*` helper when the user sends [CANCEL]. */
class ConversationCancelledException(message: String = "Conversation cancelled by user") : Exception(message)

/** Result of parsing free-form user input into a value of type [T]. */
sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Err(val message: String) : Parsed<Nothing>
}

private fun String.orCancel(): String =
    if (trim() == CANCEL) throw ConversationCancelledException() else this

private suspend fun BehaviourContext.nextText(chatId: IdChatIdentifier): String =
    waitTextMessage()
        .filter { it.chat.id == chatId }
        .first()
        .content.text.orCancel().trim()

/**
 * Run [block] as a tracked conversation for [userId].
 *
 * The block is launched in its own update-consuming sub-context (a [Job]) and registered in
 * [ConversationState]. When it finishes — normally, via [ConversationCancelledException], or
 * because the job was cancelled through [ConversationState.cancel] — the registration is removed.
 *
 * If a conversation is already running for [userId] the freshly launched job is cancelled
 * immediately, so callers should check [ConversationState.hasActive] first to inform the user
 * instead of silently dropping the second attempt.
 */
suspend fun BehaviourContext.startConversation(
    userId: Long,
    block: suspend BehaviourContext.() -> Unit,
) {
    val job = createSubContextAndDoAsynchronouslyWithUpdatesFilter(
        updatesUpstreamFlow = allUpdatesFlow
    ) {
        try {
            block()
        } catch (e: ConversationCancelledException) {
            throw e
        } finally {
            ConversationState.end(userId)
        }
    }
    if (!ConversationState.start(userId, job)) {
        job.cancel()
    }
}

/** Build a one-button-per-row reply keyboard from [options]. */
fun replyKeyboardOf(options: List<String>) = replyKeyboard {
    options.forEach { option -> row { simpleButton(option) } }
}

/**
 * Send [prompt] (when given) and wait for one trimmed text message from [chatId].
 * [CANCEL] aborts.
 */
suspend fun BehaviourContext.waitText(
    chatId: IdChatIdentifier,
    prompt: String? = null,
): String {
    prompt?.let { sendMessage(chatId, it) }
    return nextText(chatId)
}

/**
 * Send [prompt], wait for text, run [validate] (returns an error string to show the user, or
 * null when the input is acceptable). On invalid input the error is sent and [prompt] repeats.
 * Returns null when [allowSkip] is true and the user sends [SKIP]. [CANCEL] aborts.
 */
suspend fun BehaviourContext.waitValidatedText(
    chatId: IdChatIdentifier,
    prompt: String,
    allowSkip: Boolean = false,
    validate: (String) -> String? = { null },
): String? {
    while (true) {
        sendMessage(chatId, prompt)
        val text = nextText(chatId)
        if (allowSkip && text == SKIP) return null
        val error = validate(text)
        if (error == null) return text
        sendMessage(chatId, error)
    }
}

/**
 * Send [prompt], wait for text and parse it with [parse]. On [Parsed.Err] the message is shown
 * and [prompt] repeats; on [Parsed.Ok] the value is returned. Returns null when [allowSkip] is
 * true and the user sends [SKIP]. [CANCEL] aborts.
 */
suspend fun <T> BehaviourContext.waitParsed(
    chatId: IdChatIdentifier,
    prompt: String,
    allowSkip: Boolean = false,
    parse: (String) -> Parsed<T>,
): T? {
    while (true) {
        sendMessage(chatId, prompt)
        val text = nextText(chatId)
        if (allowSkip && text == SKIP) return null
        when (val p = parse(text)) {
            is Parsed.Err -> sendMessage(chatId, p.message)
            is Parsed.Ok -> return p.value
        }
    }
}

/**
 * Send [prompt] with [options] as a reply keyboard and wait until the user picks one of them.
 * Returns null when [allowSkip] is true and the user sends [SKIP]. [CANCEL] aborts.
 */
suspend fun BehaviourContext.waitEnum(
    chatId: IdChatIdentifier,
    prompt: String,
    options: List<String>,
    allowSkip: Boolean = false,
    invalidMessage: String = "Choose one of the offered options",
): String? {
    while (true) {
        send(chatId, text = prompt, replyMarkup = replyKeyboardOf(options))
        val text = nextText(chatId)
        if (allowSkip && text == SKIP) return null
        if (text in options) return text
        sendMessage(chatId, invalidMessage)
    }
}

/**
 * Send [prompt] with a yes/no reply keyboard and return whether the user picked [yes].
 * [CANCEL] aborts.
 */
suspend fun BehaviourContext.waitConfirmation(
    chatId: IdChatIdentifier,
    prompt: String,
    yes: String = "да",
    no: String = "нет",
): Boolean {
    val keyboard = replyKeyboard {
        row { simpleButton(yes) }
        row { simpleButton(no) }
    }
    send(chatId, text = prompt, replyMarkup = keyboard)
    return nextText(chatId) == yes
}

/**
 * Collect several values one at a time. Each round sends [prompt], parses the reply with [parse]
 * and adds the value to a set (rejecting duplicates). The user ends the list by sending
 * [COMPLETE]; collection also stops after [maxCount] values. [CANCEL] aborts.
 */
suspend fun <T> BehaviourContext.waitMultiple(
    chatId: IdChatIdentifier,
    prompt: String,
    maxCount: Int = Int.MAX_VALUE,
    duplicateMessage: String = "Already added",
    savedMessage: String = "Saved. Send the next one or $COMPLETE",
    parse: (String) -> Parsed<T>,
): Set<T> {
    val results = LinkedHashSet<T>()
    while (results.size < maxCount) {
        sendMessage(chatId, prompt)
        val text = nextText(chatId)
        if (text == COMPLETE) break
        when (val p = parse(text)) {
            is Parsed.Err -> sendMessage(chatId, p.message)
            is Parsed.Ok ->
                if (results.add(p.value)) sendMessage(chatId, savedMessage)
                else sendMessage(chatId, duplicateMessage)
        }
    }
    return results
}

/**
 * Send [prompt] with an inline [keyboard] whose buttons carry callback data shaped as
 * `"<prefix>|<payload>"`. Waits for a press on that exact message, acknowledges the query,
 * clears the keyboard and returns the payload (null when the payload part is empty).
 *
 * This is the inline-keyboard counterpart of [waitEnum]: use it when choices should appear as
 * tap-able inline buttons attached to the prompt rather than a reply keyboard.
 */
suspend fun BehaviourContext.waitInlineChoice(
    chatId: IdChatIdentifier,
    prompt: String,
    keyboard: InlineKeyboardMarkup,
    prefix: String,
): String? {
    val sent = sendMessage(chatId, prompt, replyMarkup = keyboard)
    val query = waitMessageDataCallbackQuery()
        .first { it.message.messageId == sent.messageId && it.data.startsWith("$prefix|") }
    runCatching { answerCallbackQuery(query) }
    runCatching { editMessageReplyMarkup(chatId = sent.chat.id, messageId = sent.messageId, replyMarkup = null) }
    return query.data.removePrefix("$prefix|").takeIf { it.isNotEmpty() }
}
