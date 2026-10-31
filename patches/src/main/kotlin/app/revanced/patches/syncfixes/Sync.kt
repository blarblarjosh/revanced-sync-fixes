package app.revanced.patches.syncfixes

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

internal val SYNC_COMPATIBILITY = arrayOf(
    "com.laurencedawson.reddit_sync" to setOf("v23.06.30-13:39"),
    "com.laurencedawson.reddit_sync.pro" to setOf("v23.06.30-13:39"),
    "com.laurencedawson.reddit_sync.dev" to setOf("v23.06.30-13:39"),
)

internal const val EXTENSION = "extensions/syncfixes.rve"
internal const val REDDIT = "Lapp/revanced/extension/syncfixes/Reddit;"
private const val NETWORK_RESPONSE = "Lcom/android/volley/NetworkResponse;"
private const val VOLLEY_RESPONSE = "Lcom/android/volley/Response;"

/** OAuthGalleryRequest's static URL builder. */
internal val galleryUrlFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returns("Ljava/lang/String;")
    parameters("Ljava/lang/String;")
    strings("?is_gallery=true", "https://oauth.reddit.com/api/info.json?id=t3_")
}

/** OAuthCommentsMoreRequest's constructor (POST to api/morechildren). */
internal val moreRequestFingerprint = fingerprint {
    returns("V")
    parameters(
        "Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;",
        "Ljava/lang/String;", "I", "I", "Ljava/lang/String;",
        "Lcom/android/volley/Response\$Listener;", "Lcom/android/volley/Response\$ErrorListener;",
    )
    strings("api/morechildren?always_show_media=1&profile_img=true", "children", "link_id")
    custom { method, _ -> method.name == "<init>" }
}

/** The comments presenter's "load more" handler: logs "Requesting more: " with the "more" row. */
internal val requestMoreFingerprint = fingerprint {
    returns("V")
    parameters("L")
    strings("CommentsPresenterImpl", "Requesting more: ")
}

/** parseNetworkResponse of the class holding [method]. */
internal fun BytecodePatchContext.parseMethod(owner: MutableMethod): MutableMethod {
    val classDef = classes.firstOrNull { it.type == owner.definingClass }
        ?: throw PatchException("${owner.definingClass} not found")
    return proxy(classDef).mutableClass.methods.singleOrNull {
        it.name == "parseNetworkResponse" && it.returnType == VOLLEY_RESPONSE &&
            it.parameterTypes.map { p -> p.toString() } == listOf(NETWORK_RESPONSE)
    } ?: throw PatchException("parseNetworkResponse not found in ${owner.definingClass}")
}

/** At the very start (all locals free) swap the NetworkResponse for the converted one. */
internal fun MutableMethod.hookResponse() {
    val p0 = implementation!!.registerCount - 2
    val p1 = p0 + 1
    if (p1 > 255) throw PatchException("too many registers in $definingClass->$name")
    addInstructions(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            invoke-static { v0, v1 }, $REDDIT->response(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $NETWORK_RESPONSE
            move-object/from16 p1, v0
        """,
    )
}

/** Shared by both patches: ships the extension. */
internal val syncExtensionPatch = bytecodePatch {
    compatibleWith(*SYNC_COMPATIBILITY)
    extendWith(EXTENSION)
}

@Suppress("unused")
val fixGalleriesPatch = bytecodePatch(
    name = "Fix Reddit galleries",
    description = "Fixes Reddit galleries failing to open since Reddit blocked /api/info (Oct 2026). " +
        "Sync loads the post from /comments instead.",
) {
    compatibleWith(*SYNC_COMPATIBILITY)
    dependsOn(syncExtensionPatch)

    execute {
        val build = galleryUrlFingerprint.method
        val returns = build.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        if (returns.size != 1) throw PatchException("gallery URL builder: expected 1 return, found ${returns.size}")
        val ret = returns.single().index
        val reg = (build.instructions[ret] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
        build.addInstructions(
            ret,
            """
                invoke-static { v$reg }, $REDDIT->galleryUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$reg
            """,
        )
        parseMethod(build).hookResponse()
    }
}

@Suppress("unused")
val fixLoadMoreCommentsPatch = bytecodePatch(
    name = "Fix load more comments",
    description = "Fixes \"load more comments\" since Reddit blocked /api/morechildren (Oct 2026). " +
        "Sync loads the missing comments from /comments instead.",
) {
    compatibleWith(*SYNC_COMPATIBILITY)
    dependsOn(syncExtensionPatch)

    execute {
        // Remember the parent of the tapped "more" row: morechildren didn't need it, /comments does.
        requestMoreFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, $REDDIT->moreBegin(Ljava/lang/Object;)V",
        )

        val ctor = moreRequestFingerprint.method
        val superCall = ctor.instructions.withIndex().filter { (_, insn) ->
            insn.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                ((insn as ReferenceInstruction).reference.toString()).endsWith(
                    "-><init>(Landroid/content/Context;ILjava/lang/String;" +
                        "Lcom/android/volley/Response\$Listener;Lcom/android/volley/Response\$ErrorListener;)V",
                )
        }.singleOrNull() ?: throw PatchException("morechildren request: super constructor call not found")
        val start = (superCall.value as RegisterRangeInstruction).startRegister
        val method = start + 2
        val url = start + 3
        if (url > 15) throw PatchException("morechildren request: unexpected registers")
        // p3 = link id, p5 = children, p8 = sort (instance method: p0 is this).
        ctor.addInstructions(
            superCall.index,
            """
                move-object/from16 v$method, p3
                move-object/from16 v${start + 4}, p5
                move-object/from16 v${start + 5}, p8
                invoke-static { v$url, v$method, v${start + 4}, v${start + 5} }, $REDDIT->moreUrl(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$url
                const/4 v$method, 0x0
                move-object/from16 v${start + 4}, p9
                move-object/from16 v${start + 5}, p10
            """,
        )
        parseMethod(ctor).hookResponse()
    }
}
