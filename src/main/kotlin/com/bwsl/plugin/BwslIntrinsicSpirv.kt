package com.bwsl.plugin

private const val SPIRV_SPEC = "https://registry.khronos.org/SPIR-V/specs/unified1/SPIRV.html"
private const val GLSL_STD_450_SPEC = "https://registry.khronos.org/SPIR-V/specs/unified1/GLSL.std.450.html"

/**
 * The SPIR-V instructions each intrinsic is emitted as, taken from the compiler's intrinsic table
 * (`SPV_MAP` in `core/bwsl_stdlib.h`) and the comments beside it. A name starting with `Op` is a core
 * instruction; any other is an instruction of the `GLSL.std.450` extended set. Where the backend picks by
 * type (`clamp`: `FClamp` for floats, `SClamp`/`UClamp` for integers) every candidate is listed.
 *
 * An intrinsic that is not here has no single instruction behind it: the compiler lowers it to several
 * (`sincos`, listed with both of its) or its table row names none (`rcp`, `log10`, `isfinite`, ...), and
 * the offset variants of the sampling intrinsics (`sample_offset`, ...) are not listed because the table
 * does not say which instruction they use.
 */
private val SPIRV_INSTRUCTIONS: Map<String, List<String>> = mapOf(
    // Math
    "lerp" to listOf("FMix"), "smoothstep" to listOf("SmoothStep"), "saturate" to listOf("FClamp"),
    "fract" to listOf("Fract"), "step" to listOf("Step"),
    "clamp" to listOf("FClamp", "SClamp", "UClamp"), "sign" to listOf("FSign", "SSign"),
    "abs" to listOf("FAbs", "SAbs"), "min" to listOf("FMin", "SMin", "UMin"), "max" to listOf("FMax", "SMax", "UMax"),
    "floor" to listOf("Floor"), "ceil" to listOf("Ceil"), "round" to listOf("RoundEven"), "trunc" to listOf("Trunc"),
    "mod" to listOf("OpFMod"), "fmod" to listOf("OpFRem"), "fma" to listOf("Fma"), "pow" to listOf("Pow"),
    "sqrt" to listOf("Sqrt"), "rsqrt" to listOf("InverseSqrt"), "exp" to listOf("Exp"), "exp2" to listOf("Exp2"),
    "log" to listOf("Log"), "log2" to listOf("Log2"), "frexp" to listOf("FrexpStruct"), "ldexp" to listOf("Ldexp"),
    "modf" to listOf("ModfStruct"),
    // Trigonometry
    "sin" to listOf("Sin"), "cos" to listOf("Cos"), "tan" to listOf("Tan"), "asin" to listOf("Asin"),
    "acos" to listOf("Acos"), "atan" to listOf("Atan"), "atan2" to listOf("Atan2"), "sincos" to listOf("Sin", "Cos"),
    "sinh" to listOf("Sinh"), "cosh" to listOf("Cosh"), "tanh" to listOf("Tanh"),
    "degrees" to listOf("Degrees"), "radians" to listOf("Radians"),
    // Vectors and matrices
    "dot" to listOf("OpDot"), "cross" to listOf("Cross"), "normalize" to listOf("Normalize"), "length" to listOf("Length"),
    "distance" to listOf("Distance"), "reflect" to listOf("Reflect"), "refract" to listOf("Refract"),
    "faceforward" to listOf("FaceForward"), "transpose" to listOf("OpTranspose"),
    "determinant" to listOf("Determinant"), "inverse" to listOf("MatrixInverse"),
    // Derivatives
    "ddx" to listOf("OpDPdx"), "ddy" to listOf("OpDPdy"), "ddx_fine" to listOf("OpDPdxFine"),
    "ddy_fine" to listOf("OpDPdyFine"), "ddx_coarse" to listOf("OpDPdxCoarse"), "ddy_coarse" to listOf("OpDPdyCoarse"),
    "fwidth" to listOf("OpFwidth"), "fwidth_fine" to listOf("OpFwidthFine"), "fwidth_coarse" to listOf("OpFwidthCoarse"),
    // Textures and images
    "sample" to listOf("OpImageSampleImplicitLod"), "sample_lod" to listOf("OpImageSampleExplicitLod"),
    "sample_grad" to listOf("OpImageSampleExplicitLod"), "sample_bias" to listOf("OpImageSampleImplicitLod"),
    "sample_cmp" to listOf("OpImageSampleDrefImplicitLod"), "gather" to listOf("OpImageGather"),
    "gather_offset" to listOf("OpImageGather"), "load" to listOf("OpImageFetch"), "load_offset" to listOf("OpImageFetch"),
    "store" to listOf("OpImageWrite"), "texture_size" to listOf("OpImageQuerySizeLod"),
    "texture_levels" to listOf("OpImageQueryLevels"),
    // Synchronisation
    "barrier" to listOf("OpControlBarrier"), "memoryBarrier" to listOf("OpMemoryBarrier"),
    "storageBarrier" to listOf("OpMemoryBarrier"),
    // Wave operations
    "wave_sum" to listOf("OpGroupNonUniformFAdd"), "wave_product" to listOf("OpGroupNonUniformFMul"),
    "wave_min" to listOf("OpGroupNonUniformFMin"), "wave_max" to listOf("OpGroupNonUniformFMax"),
    "wave_all" to listOf("OpGroupNonUniformAll"), "wave_any" to listOf("OpGroupNonUniformAny"),
    "wave_broadcast" to listOf("OpGroupNonUniformBroadcast"), "wave_read_first" to listOf("OpGroupNonUniformBroadcastFirst"),
    // Atomics
    "atomic_add" to listOf("OpAtomicIAdd"), "atomic_min" to listOf("OpAtomicSMin"), "atomic_max" to listOf("OpAtomicSMax"),
    "atomic_and" to listOf("OpAtomicAnd"), "atomic_or" to listOf("OpAtomicOr"), "atomic_xor" to listOf("OpAtomicXor"),
    "atomic_exchange" to listOf("OpAtomicExchange"), "atomic_cmp_exchange" to listOf("OpAtomicCompareExchange"),
    // Bits
    "count_bits" to listOf("OpBitCount"), "reverse_bits" to listOf("OpBitReverse"),
    "first_bit_low" to listOf("FindILsb"), "first_bit_high" to listOf("FindSMsb", "FindUMsb"),
    "bitfield_extract" to listOf("OpBitFieldSExtract"), "bitfield_insert" to listOf("OpBitFieldInsert"),
    "pack_unorm2x16" to listOf("PackUnorm2x16"), "unpack_unorm2x16" to listOf("UnpackUnorm2x16"),
    "pack_unorm4x8" to listOf("PackUnorm4x8"), "unpack_unorm4x8" to listOf("UnpackUnorm4x8"),
    "pack_snorm2x16" to listOf("PackSnorm2x16"), "unpack_snorm2x16" to listOf("UnpackSnorm2x16"),
    "pack_snorm4x8" to listOf("PackSnorm4x8"), "unpack_snorm4x8" to listOf("UnpackSnorm4x8"),
    "pack_half2x16" to listOf("PackHalf2x16"), "unpack_half2x16" to listOf("UnpackHalf2x16"),
    "asfloat" to listOf("OpBitcast"), "asint" to listOf("OpBitcast"), "asuint" to listOf("OpBitcast"),
    // Selection, reductions, classification
    "select" to listOf("OpSelect"), "any" to listOf("OpAny"), "all" to listOf("OpAll"),
    "isnan" to listOf("OpIsNan"), "isinf" to listOf("OpIsInf")
)

/** The SPIR-V instruction names [intrinsic] is emitted as, or empty when no single instruction is behind it. */
fun findSpirvInstructionsOf(intrinsic: String): List<String> = SPIRV_INSTRUCTIONS[intrinsic].orEmpty()

/** Every intrinsic that has a mapping, for checking the table against the compiler's. */
internal fun collectSpirvMappedIntrinsics(): Map<String, List<String>> = SPIRV_INSTRUCTIONS

// The GLSL.std.450 page has no anchor per instruction, so a link selects the instruction's name with a text
// fragment (`#:~:text=`): a browser that supports them scrolls to it and highlights it, any other opens the
// page. The name is the first whole-word match in the page for all but these, whose name is also in the prose
// of another instruction (`Round` speaks of `RoundEven`), so what directly follows the definition is added.
private val GLSL_STD_450_FRAGMENT_SUFFIX = mapOf(
    "RoundEven" to "Result is the value",
    "ModfStruct" to "Result is a structure",
    "FrexpStruct" to "Result is a structure",
    "Degrees" to "Converts radians"
)

/** The link to the Khronos specification of [instruction]: a core instruction has its own anchor, an extended one is found in the page by its name. */
fun buildSpirvSpecUrl(instruction: String): String {
    if (instruction.startsWith("Op")) return "$SPIRV_SPEC#$instruction"
    val suffix = GLSL_STD_450_FRAGMENT_SUFFIX[instruction]?.let { ",-" + it.replace(" ", "%20") }.orEmpty()
    return "$GLSL_STD_450_SPEC#:~:text=$instruction$suffix"
}

/**
 * The line of the documentation popup that names the SPIR-V instructions of [intrinsic], each linked to
 * the Khronos specification; null when it has none. A `GLSL.std.450` instruction is marked as such.
 */
fun renderSpirvHtml(intrinsic: String): String? {
    val instructions = findSpirvInstructionsOf(intrinsic).takeIf { it.isNotEmpty() } ?: return null
    val links = instructions.joinToString(" / ") { name ->
        val label = if (name.startsWith("Op")) name else "GLSL.std.450 $name"
        "<a href=\"${buildSpirvSpecUrl(name)}\">$label</a>"
    }
    return "SPIR-V: $links"
}
