package io.github.bakwudo.uyu.patches.twitch.appearance

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.bakwudo.uyu.patches.util.thisRegister
import io.github.bakwudo.uyu.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.bakwudo.uyu.patches.twitch.shared.sharedExtensionPatch

private const val HOOKS = "Lapp/morphe/extension/appearance/KizuHooks;"
private const val HYPE_TRAIN = "Lapp/morphe/extension/appearance/HypeTrainHider;"

/**
 * Twitch 31.3.1: vuk.b(Object). Forwards hype train events (add / update / level-up pill /
 * expand / collapse / remove) to the community highlights presenter under the id "hypetrain".
 * The other methods with "hypetrain" return Object, so V + (Object) is unique.
 */
internal object HypeTrainHighlightEventFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("hypetrain"),
)
// Twitch 31.3.1: i76 = チャット入力欄 (chat_message_input_view), tb9 = チャンネルポイント画面 (community_points_container)
private const val CHAT_INPUT = "Li76;"
private const val COMMUNITY_POINTS = "Ltb9;"
// en9 = チャンネルポイントの報酬一覧 (reward_layout_container)
private const val REWARDS = "Len9;"

@Suppress("unused")
val kizuHooksPatch = bytecodePatch(
    name = "Kizu hooks",
    description = "Chat settings button in the player, prediction settings in the channel points screen, and hiding the hype train.",
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
        hookConstructor(REWARDS, "onRewardsCreated")

        // ハイプトレイン非表示: 削除イベント以外を入口で捨てる。失敗してもほかのフックは生かす。
        try {
            HypeTrainHighlightEventFingerprint.method.apply {
                val insns = instructions.toList()
                val equalsIndex = insns.indexOfLast {
                    (it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                        ((it as ReferenceInstruction).reference as MethodReference).let { m ->
                            m.name == "equals" && m.returnType == "Z" &&
                                m.parameterTypes.map { t -> t.toString() } == listOf("Ljava/lang/Object;")
                        }
                }
                if (equalsIndex < 0) throw PatchException("hype train: equals() not found.")
                // `event.equals(RemoveHypeTrain.INSTANCE)`: the singleton read just before equals().
                val removeField = insns.subList(0, equalsIndex).lastOrNull {
                    it.opcode == Opcode.SGET_OBJECT &&
                        ((it as ReferenceInstruction).reference as FieldReference).let { f -> f.type == f.definingClass }
                }?.let { (it as ReferenceInstruction).reference as FieldReference }
                    ?: throw PatchException("hype train: remove event singleton not found.")
                if (thisRegister == 0) throw PatchException("hype train: no free register.")
                addInstructionsWithLabels(
                    0,
                    """
                        sget-object v0, ${removeField.definingClass}->${removeField.name}:${removeField.type}
                        if-eq p1, v0, :keep
                        invoke-static {}, $HYPE_TRAIN->hideHypeTrain()Z
                        move-result v0
                        if-eqz v0, :keep
                        return-void
                        :keep
                        nop
                    """,
                )
            }
        } catch (e: Exception) {
            println("Kizu hooks: hype train hook skipped: ${e.message}")
        }
    }
}
