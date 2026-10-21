package io.github.bakwudo.uyu.patches.twitch.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import io.github.bakwudo.uyu.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.bakwudo.uyu.patches.twitch.shared.sharedExtensionPatch

private const val CHAT_ADAPTER = "Lc35;"
private const val PACER = "Lapp/morphe/extension/twitch/chat/ChatPacer;"

@Suppress("unused")
val smoothChatPatch = bytecodePatch(
    name = "Smooth chat",
    description = "Shows chat messages one at a time, each within 0.3 seconds, instead of in batches.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(sharedExtensionPatch)

    execute {
        val classDef = classDefByOrNull(CHAT_ADAPTER)
            ?: throw PatchException("Smooth chat: chat adapter c35 was not found.")
        val adapter = mutableClassDefBy(classDef)
        val method = adapter.methods.singleOrNull { m ->
            m.name == "w" &&
                m.returnType == "V" &&
                m.parameterTypes.map { it.toString() } == listOf("Ljava/util/List;")
        } ?: throw PatchException("Smooth chat: expected one c35.w(List) method.")

        val impl = method.implementation
            ?: throw PatchException("Smooth chat: c35.w has no code.")
        if (impl.registerCount - 2 < 1) {
            throw PatchException("Smooth chat: no free register in c35.w.")
        }

        val original = method.instructions.first()
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0, p1}, $PACER->intercept(Ljava/lang/Object;Ljava/util/List;)Z
                move-result v0
                if-eqz v0, :kizu_chat_original
                return-void
            """,
            ExternalLabel("kizu_chat_original", original),
        )
    }
}
