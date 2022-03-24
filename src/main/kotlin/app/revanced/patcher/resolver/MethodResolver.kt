package app.revanced.patcher.resolver

import app.revanced.patcher.cache.MethodMap
import app.revanced.patcher.cache.PatchData
import app.revanced.patcher.cache.PatternScanData
import app.revanced.patcher.signature.Signature
import app.revanced.patcher.util.ExtraTypes
import mu.KotlinLogging
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

private val logger = KotlinLogging.logger("MethodResolver")

internal class MethodResolver(private val classList: List<ClassNode>, private val signatures: Array<Signature>) {
    fun resolve(): MethodMap {
        val methodMap = MethodMap()

        for ((classNode, methods) in classList) {
            for (method in methods) {
                for (signature in signatures) {
                    if (methodMap.containsKey(signature.name)) { // method already found for this sig
                        logger.trace { "Sig ${signature.name} already found, skipping." }
                        continue
                    }
                    logger.trace { "Resolving sig ${signature.name}: ${classNode.name} / ${method.name}" }
                    val (r, sr) = cmp(method, signature)
                    if (!r || sr == null) {
                        logger.trace { "Compare result for sig ${signature.name} has failed!" }
                        continue
                    }
                    logger.trace { "Method for sig ${signature.name} found!" }
                    methodMap[signature.name] = PatchData(
                        classNode,
                        method,
                        PatternScanData(
                            // sadly we cannot create contracts for a data class, so we must assert
                            sr.startIndex!!,
                            sr.endIndex!!
                        )
                    )
                }
            }
        }

        for (signature in signatures) {
            if (methodMap.containsKey(signature.name)) continue
            logger.error { "Could not find method for sig ${signature.name}!" }
        }

        return methodMap
    }

    // These functions do not require the constructor values, so they can be static.
    companion object {
        fun resolveMethod(classNode: ClassNode, signature: Signature): PatchData? {
            for (method in classNode.methods) {
                val (r, sr) = cmp(method, signature)
                if (!r || sr == null) continue
                return PatchData(
                    classNode,
                    method,
                    PatternScanData(0, 0) // opcode list is always ignored.
                )
            }
            return null
        }

        private fun cmp(method: MethodNode, signature: Signature): Pair<Boolean, ScanResult?> {
            signature.returns?.let { _ ->
                val methodReturns = Type.getReturnType(method.desc).convertObject()
                if (signature.returns != methodReturns) {
                    logger.trace {
                        """
                            Comparing sig ${signature.name}: invalid return type:
                            expected ${signature.returns},
                            got $methodReturns
                        """.trimIndent()
                    }
                    return@cmp false to null
                }
            }

            signature.accessors?.let { _ ->
                if (signature.accessors != method.access) {
                    logger.trace {
                        """
                            Comparing sig ${signature.name}: invalid accessors:
                            expected ${signature.accessors},
                            got ${method.access}
                        """.trimIndent()
                    }
                    return@cmp false to null
                }
            }

            signature.parameters?.let { _ ->
                val parameters = Type.getArgumentTypes(method.desc).convertObjects()
                if (!signature.parameters.contentEquals(parameters)) {
                    logger.trace {
                        """
                            Comparing sig ${signature.name}: invalid parameter types:
                            expected ${signature.parameters.joinToString()}},
                            got ${parameters.joinToString()}
                        """.trimIndent()
                    }
                    return@cmp false to null
                }
            }

            signature.opcodes?.let { _ ->
                val result = method.instructions.scanFor(signature.opcodes)
                if (!result.found) {
                    logger.trace { "Comparing sig ${signature.name}: invalid opcode pattern" }
                    return@cmp false to null
                }
                return@cmp true to result
            }

            return true to ScanResult(true)
        }
    }
}

private operator fun ClassNode.component1() = this
private operator fun ClassNode.component2() = this.methods

private fun InsnList.scanFor(pattern: IntArray): ScanResult {
    for (i in 0 until this.size()) {
        var occurrence = 0
        while (i + occurrence < this.size()) {
            val n = this[i + occurrence]
            if (
                !n.anyOf(
                    LabelNode::class.java,
                    LineNumberNode::class.java
                ) &&
                n.opcode != pattern[occurrence]
            ) break
            if (++occurrence >= pattern.size) {
                val current = i + occurrence
                return ScanResult(true, current - pattern.size, current)
            }
        }
    }

    return ScanResult(false)
}

private fun Type.convertObject(): Type {
    return when (this.sort) {
        Type.OBJECT -> ExtraTypes.Any
        Type.ARRAY -> ExtraTypes.ArrayAny
        else -> this
    }
}

private fun Array<Type>.convertObjects(): Array<Type> {
    return this.map { it.convertObject() }.toTypedArray()
}

private fun AbstractInsnNode.anyOf(vararg types: Class<*>): Boolean =
    types.any { this@anyOf.javaClass == it }