package io.github.bakwudo.uyu.patches.twitch.appearance

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import io.github.bakwudo.uyu.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.bakwudo.uyu.patches.twitch.shared.sharedExtensionPatch

private const val HOOKS = "Lapp/morphe/extension/appearance/KizuHooks;"
// Twitch 31.3.1: i76 = チャット入力欄 (chat_message_input_view), tb9 = チャンネルポイント画面 (community_points_container)
private const val CHAT_INPUT = "Li76;"
private const val COMMUNITY_POINTS = "Ltb9;"

@Suppress("unused")
val kizuHooksPatch = bytecodePatch(
    name = "Kizu hooks",
    description = "Chat settings button in the player and prediction settings in the channel points screen.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(sharedExtensionPatch)

    execute {
        fun hookConstructor(type: String, callback: String) {
            val def = classDefByOrNull(type) ?: throw PatchException("Kizu hooks: $type not found.")
            val cls = mutableClassDefBy(def)
            val ctor = cls.methods.singleOrNull { it.name == "<init>" }
                ?: throw PatchException("Kizu hooks: one constructor expected in $type.")
            val returns = ctor.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
                .sortedDescending()
            if (returns.isEmpty()) throw PatchException("Kizu hooks: no return in $type.")
            for (i in returns) {
                ctor.addInstruction(i, "invoke-static {p0}, $HOOKS->$callback(Ljava/lang/Object;)V")
            }
        }
        hookConstructor(CHAT_INPUT, "onChatInputCreated")
        hookConstructor(COMMUNITY_POINTS, "onCommunityPointsCreated")
    }
}
