package com.github.thecatinyourceiling

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.CommandContext
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ChannelUtils
import com.aliucord.utils.ReflectUtils
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.message.LocalAttachment
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.attachments.AttachmentUtilsKt
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.chat.input.`WidgetChatInput$configureSendListeners$2`
import com.github.thecatinyourceiling.gif.AnimatedGifEncoder
import com.lytefast.flexinput.model.Attachment
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URL

private const val DEFAULT_DELAY_MS = 20
private const val DEFAULT_RESOLUTION = 128
private const val AVATAR_SIZE = 2048
private const val FRAME_COUNT = 10
private const val OUTPUT_FILE_NAME = "petpet.gif"
private const val NO_IMAGE_SPECIFIED = "No image specified."
private const val TRANSPARENT_SENTINEL = 0xFFFF00FF.toInt()
private const val STALE_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1000L

@AliucordPlugin(
    requiresRestart = false,
)
@Suppress("unused")
class PetPet : Plugin() {
    private var handFrames: List<Bitmap>? = null

    override fun start(context: Context) {
        patchCommandContextConstructor()
        // (Shamelessly) stolen from Equicord's petpet plugin
        commands.registerCommand(
            "petpet",
            "Create a petpet gif.",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.INTEGER,
                    "delay",
                    "The delay between each frame in ms. Rounded to nearest 10ms. Defaults to the minimum value of 20.",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.INTEGER,
                    "resolution",
                    "Resolution for the gif. Defaults to 128.",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.ATTACHMENT,
                    "image",
                    "Image attachment to use",
                ),
                Utils.createCommandOption(ApplicationCommandType.STRING, "url", "URL to fetch image from"),
                Utils.createCommandOption(ApplicationCommandType.USER, "user", "User whose avatar to use as image"),
                Utils.createCommandOption(
                    ApplicationCommandType.BOOLEAN,
                    "no-server-pfp",
                    "Use the normal avatar instead of the server specific one when using the 'user' option",
                ),
            ),
        ) { ctx -> executePetPet(ctx) }
    }

    override fun stop(context: Context) {
        commands.unregisterAll()
        patcher.unpatchAll()
        handFrames?.forEach { frame ->
            if (!frame.isRecycled) frame.recycle()
        }
        handFrames = null
    }

    private fun localError(message: String) = CommandsAPI.CommandResult(message, null, false)

    private fun resetAttachments(ctx: CommandContext) {
        ReflectUtils.setField(ctx, "attachments", ArrayList<Attachment<*>>())
    }

    @Suppress("UNCHECKED_CAST")
    private fun patchCommandContextConstructor() {
        val constructor = CommandContext::class.java.declaredConstructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 4 &&
                Map::class.java.isAssignableFrom(types[0]) &&
                types[2].isArray &&
                types[3] == MessageContent::class.java
        } ?: run {
            logger.warn("Failed CommandContext constructor find")
            return
        }

        // Yapping time:
        // Aliucord's CommandContext constructor force-casts _args[2] to WidgetChatInput$configureSendListeners$7$1
        // This causes a lot of issues as it seems like the send button normally uses $6$1 instead
        // Aliucord *used* to cast it to $6$1 but for some reason it was changed in 2021
        // What makes this funny is that a few months later they *did* start doing the correct approach in an update to CommandsAPI, but CommandContext was never updated
        patcher.patch(constructor, PreHook { param ->
            val commandArgs = param.args[0] as? Map<*, *> ?: return@PreHook
            val sendListener = param.args[1] as? `WidgetChatInput$configureSendListeners$2` ?: return@PreHook
            val rawHookArgs = param.args[2] as? Array<*> ?: return@PreHook
            val messageContent = param.args[3] as? MessageContent ?: return@PreHook
            val viewState = WidgetChatInput.`access$getViewModel$p`(sendListener.`this$0`).viewState
                as? ChatInputViewModel.ViewState.Loaded ?: return@PreHook
            val attachments = rawHookArgs.getOrNull(0) as? List<Attachment<*>> ?: emptyList()

            try {
                ReflectUtils.setFinalField(CommandContext::class.java, param.thisObject, "args", commandArgs)
                ReflectUtils.setFinalField(CommandContext::class.java, param.thisObject, "_this", sendListener)
                ReflectUtils.setFinalField(CommandContext::class.java, param.thisObject, "messageContent", messageContent)
                ReflectUtils.setFinalField(CommandContext::class.java, param.thisObject, "viewState", viewState)
                ReflectUtils.setField(CommandContext::class.java, param.thisObject, "attachments", attachments)
                param.result = null
            } catch (throwable: Throwable) {
                logger.error("Failed to patch CommandContext construction", throwable)
            }
        })
    }

    private fun executePetPet(ctx: CommandContext): CommandsAPI.CommandResult {
        fun error(message: String): CommandsAPI.CommandResult {
            resetAttachments(ctx)
            return localError(message)
        }

        val context = ctx.context
        val frames = getHandFrames(context)

        val noServerPfp = ctx.getBoolOrDefault("no-server-pfp", false)
        var avatar: Bitmap? = null

        try {
            avatar = resolveSourceBitmap(ctx, noServerPfp)

            val delay = ctx.getIntOrDefault("delay", DEFAULT_DELAY_MS)
            if (delay < DEFAULT_DELAY_MS) return error("Delay must be at least 20.")

            val resolution = ctx.getIntOrDefault("resolution", DEFAULT_RESOLUTION)
            if (resolution < 1) return error("Resolution must be at least 1.")

            deleteStaleOutputFiles(context.cacheDir)

            val outputFile = File(context.cacheDir, "petpet-${System.currentTimeMillis()}.gif")
            try {
                FileOutputStream(outputFile).use { output ->
                    renderPetPetGif(output, avatar, frames, resolution, delay)
                }
            } catch (throwable: Throwable) {
                outputFile.delete()
                logger.error("Failed to create PetPet GIF", throwable)
                return error("Failed to create PetPet GIF")
            }

            val maxFileSizeBytes = ctx.maxFileSizeMB * 1024L * 1024L
            if (outputFile.length() > maxFileSizeBytes) {
                outputFile.delete()
                return error("Generated GIF is too large to upload. Try a lower resolution.")
            }

            resetAttachments(ctx)
            ctx.addAttachment(Uri.fromFile(outputFile).toString(), OUTPUT_FILE_NAME)
            return CommandsAPI.CommandResult("\u200b", null, true)
        } finally {
            avatar?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun resolveSourceBitmap(
        ctx: CommandContext,
        noServerPfp: Boolean,
    ): Bitmap {
        val context = ctx.context
        val imageOptionError = try {
            resolveImageOptionAttachment(ctx)?.let { attachment ->
                return decodeBitmapFromAttachment(context, attachment)
            }
            null
        } catch (error: CommandError) {
            error
        }

        val url = ctx.getString("url")?.trim()
        if (url != null && hasNonBlank(url)) return decodeBitmapFromUrl(url)

        val user = ctx.getUser("user")
        if (user != null) return decodeUserAvatar(ctx, user, noServerPfp)

        if (ctx.attachments.size == 1) return decodeBitmapFromAttachment(context, ctx.attachments[0])

        if (imageOptionError != null) throw imageOptionError
        return decodeUserAvatar(ctx, resolveDefaultUser(ctx), noServerPfp)
    }

    private fun resolveImageOptionAttachment(ctx: CommandContext): Attachment<*>? {
        val rawImage = commandArgValue(ctx.get("image")) ?: return null
        val attachments = ctx.attachments

        val resolved = when (rawImage) {
            is Attachment<*> -> rawImage
            is LocalAttachment -> AttachmentUtilsKt.toAttachment(rawImage)
            is Number -> findAttachmentById(attachments, rawImage.toLong())
            is String -> {
                val image = rawImage.trim()
                if (!hasNonBlank(image)) {
                    null
                } else {
                    val id = image.toLongOrNull()
                    if (id != null) {
                        findAttachmentById(attachments, id)
                    } else {
                        attachments.firstOrNull { attachment ->
                            attachment.displayName == image || "SPOILER_${attachment.displayName}" == image
                        }
                    }
                }
            }
            else -> null
        }

        if (resolved != null) return resolved
        if (attachments.size == 1) return attachments[0]

        throw CommandError(NO_IMAGE_SPECIFIED)
    }

    private fun findAttachmentById(attachments: List<Attachment<*>>, id: Long): Attachment<*>? =
        attachments.firstOrNull { attachment -> attachment.id == id }

    private fun commandArgValue(raw: Any?): Any? {
        if (raw == null || raw is Number || raw is String || raw is Boolean || raw is Attachment<*> || raw is LocalAttachment) {
            return raw
        }

        val valueMethod = raw.javaClass.methods.firstOrNull { method ->
            method.name == "getValue" && method.parameterTypes.isEmpty()
        } ?: return raw

        return try {
            valueMethod.invoke(raw)
        } catch (_: Throwable) {
            raw
        }
    }

    private fun decodeBitmapFromAttachment(context: Context, attachment: Attachment<*>): Bitmap {
        if (!AttachmentUtilsKt.isImage(attachment, context.contentResolver)) throw CommandError("Upload is not an image")
        return decodeBitmapFromUri(context, attachment.uri)
    }

    private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw CommandError("Failed to decode image.")

    private fun decodeBitmapFromUrl(url: String): Bitmap {
        try {
            val parsedUrl = URL(url)
            if (parsedUrl.protocol != "http" && parsedUrl.protocol != "https") {
                throw IllegalArgumentException("Link protocol must be http or https. Make sure you're pasting the link in correctly.")
            }

            val connection = parsedUrl.openConnection()
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            connection.getInputStream().use { stream ->
                return BitmapFactory.decodeStream(stream) ?: throw CommandError("Failed to decode remote image.")
            }
        } catch (throwable: Throwable) {
            logger.error("Failed to load image URL", throwable)
            throw CommandError("Failed to load image. Check the debug log.")
        }
    }

    private fun resolveDefaultUser(ctx: CommandContext): User =
        ChannelUtils.getDMRecipient(ctx.currentChannel.raw()) ?: ctx.me

    private fun decodeUserAvatar(ctx: CommandContext, user: User, noServerPfp: Boolean): Bitmap {
        val guildId = ctx.currentChannel.guildId
        val guildAvatarHash = if (noServerPfp || guildId == 0L) {
            null
        } else {
            StoreStream.getGuilds().members[guildId]?.get(user.id)?.avatarHash
        }
        val userAvatar = user.avatar
        // I have to do this because the IconUtils size fix isn't in stable Aliucord yet
        val url = when {
            hasNonBlank(guildAvatarHash) -> discordCdnImageUrl("guilds/$guildId/users/${user.id}/avatars/$guildAvatarHash")
            userAvatar != null && hasNonBlank(userAvatar) && user.id == -1L -> userAvatar
            userAvatar != null && hasNonBlank(userAvatar) -> discordCdnImageUrl("avatars/${user.id}/$userAvatar")
            else -> "https://cdn.discordapp.com/embed/avatars/${user.discriminator % 5}.png"
        }

        return decodeBitmapFromUrl(url)
    }

    private fun discordCdnImageUrl(path: String): String =
        "https://cdn.discordapp.com/$path.png?size=$AVATAR_SIZE"

    // For some reason, just calling the isNullOrBlank included with the kotlin stdlib crashes the plugin.
    // 1. isNullOrBlank calls kotlin/text/StringsKt.isBlank(CharSequence)
    // 2. isBlank() iterates CharSequence.indices and casts the iterator to kotlin.collections.IntIterator
    // 3. Aliucord or whatever gives it "d0.d0.b" which makes it throw ClassCastException :D
    // I love that I decided to do this as my first Kotlin project.
    private fun hasNonBlank(value: String?): Boolean {
        if (value == null) return false

        var index = 0
        while (index < value.length) {
            if (!Character.isWhitespace(value[index])) return true
            index += 1
        }

        return false
    }

    private fun getHandFrames(context: Context): List<Bitmap> {
        handFrames?.let { return it }

        val classLoader = javaClass.classLoader ?: throw CommandError("Failed to load petpet frame resources.")
        val frames = (0 until FRAME_COUNT).map { index ->
            val path = "res/raw/pet$index.png"
            val stream = classLoader.getResourceAsStream(path) ?: throw CommandError("Failed to load petpet frame resources.")
            stream.use { input ->
                BitmapFactory.decodeStream(input) ?: throw CommandError("Failed to load petpet frame resources.")
            }
        }
        handFrames = frames
        return frames
    }

    // Tried fixing this lint warning but couldn't. Who cares anyway?
    @SuppressLint("UseKtx")
    private fun renderPetPetGif(
        output: OutputStream,
        avatar: Bitmap,
        frames: List<Bitmap>,
        resolution: Int,
        delay: Int,
    ) {
        val encoder = AnimatedGifEncoder()
        encoder.setSize(resolution, resolution)
        encoder.setRepeat(0)
        encoder.setDelay(delay)
        encoder.setDispose(2)
        encoder.setTransparent(TRANSPARENT_SENTINEL)
        encoder.setQuality(10)

        encoder.start(output)

        val frameBitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
        var finished = false
        try {
            val canvas = Canvas(frameBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            }

            for (i in 0 until FRAME_COUNT) {
                frameBitmap.eraseColor(TRANSPARENT_SENTINEL)
                val j = if (i < FRAME_COUNT / 2) i else FRAME_COUNT - i
                val width = 0.8f + j * 0.02f
                val height = 0.8f - j * 0.05f
                val offsetX = (1f - width) * 0.5f + 0.1f
                val offsetY = 1f - height - 0.08f
                canvas.drawBitmap(
                    avatar,
                    null,
                    RectF(
                        offsetX * resolution,
                        offsetY * resolution,
                        (offsetX + width) * resolution,
                        (offsetY + height) * resolution,
                    ),
                    paint,
                )
                canvas.drawBitmap(
                    frames[i],
                    null,
                    RectF(0f, 0f, resolution.toFloat(), resolution.toFloat()),
                    paint,
                )
                encoder.addFrame(frameBitmap)
            }
            encoder.finish()
            finished = true
        } finally {
            if (!finished) encoder.finish()
            frameBitmap.recycle()
        }
    }

    private fun deleteStaleOutputFiles(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - STALE_OUTPUT_MAX_AGE_MS
        cacheDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("petpet-") &&
                file.name.endsWith(".gif") &&
                file.lastModified() < cutoff
        }?.forEach { file -> file.delete() }
    }

    private class CommandError(message: String) : Exception(message)
}
