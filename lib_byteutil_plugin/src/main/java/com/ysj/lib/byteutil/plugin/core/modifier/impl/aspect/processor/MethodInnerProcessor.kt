package com.ysj.lib.byteutil.plugin.core.modifier.impl.aspect.processor

import com.ysj.lib.byteutil.api.aspect.POSITION_RETURN
import com.ysj.lib.byteutil.api.aspect.POSITION_START
import com.ysj.lib.byteutil.plugin.core.logger.YLogger
import com.ysj.lib.byteutil.plugin.core.modifier.impl.aspect.AspectModifier
import com.ysj.lib.byteutil.plugin.core.modifier.impl.aspect.PointcutBean
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.regex.Pattern

/**
 * 方法内部修改处理器
 *
 * @author Ysj
 * Create time: 2021/8/15
 */
class MethodInnerProcessor(aspectModifier: AspectModifier) : BaseMethodProcessor(aspectModifier) {

    private val logger = YLogger.getLogger(javaClass)

    val targetClass by lazy { HashSet<PointcutBean>() }

    val targetSuperClass by lazy { HashSet<PointcutBean>() }

    val targetInterface by lazy { HashSet<PointcutBean>() }

    val targetAnnotation by lazy { HashSet<PointcutBean>() }

    fun process(pointcut: PointcutBean, classNode: ClassNode, methodNode: MethodNode) {
        if (pointcut.position != POSITION_RETURN && pointcut.position != POSITION_START) return
        val insnList = methodNode.instructions
        val firstLabel = if (methodNode.name != "<init>") insnList.first else {
            var result: AbstractInsnNode? = null
            val iterator = insnList.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (next.opcode == Opcodes.INVOKESPECIAL) {
                    result = next.next
                    break
                }
            }
            result
        } ?: return
        // 切面方法的参数
        val hasJoinPoint = pointcut.aspectFunArgs.isNotEmpty()
        if (hasJoinPoint && !methodNode.isStoredJoinPoint) {
            insnList.insertBefore(firstLabel, storeJoinPoint(classNode, methodNode))
        }
        // 将 Pointcut 和 JointPoint 连接 XXX.instance.xxxfun(jointPoint);
        val callAspectFun: (AbstractInsnNode) -> Unit = {
            insnList.insertBefore(it, InsnList().apply {
                add(FieldInsnNode(
                    Opcodes.GETSTATIC,
                    pointcut.aspectClassName,
                    "instance",
                    Type.getObjectType(pointcut.aspectClassName).descriptor
                ))
                if (hasJoinPoint) add(getJoinPoint(classNode, methodNode))
                add(MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    pointcut.aspectClassName,
                    pointcut.aspectFunName,
                    pointcut.aspectFunDesc,
                    false
                ))
            })
        }
        when (pointcut.position) {
            POSITION_START -> callAspectFun(firstLabel)
            POSITION_RETURN -> for (insnNode in insnList) {
                if (insnNode.opcode !in Opcodes.IRETURN..Opcodes.RETURN) continue
                callAspectFun(insnNode)
            }
        }
        logger.info("Method Inner 插入 --> ${classNode.name}#${methodNode.name}${methodNode.desc}")
    }

    /**
     * 查找类中所有的切入点
     */
    fun findPointcuts(classNode: ClassNode): ArrayList<PointcutBean> {
        val pointcuts = ArrayList<PointcutBean>()
        // 查找类中的
        targetClass.forEach { if (Pattern.matches(it.target, classNode.name)) pointcuts.add(it) }
        // 查找父类中的
        for (it in targetSuperClass) {
            fun findSuperClass(superName: String?) {
                superName ?: return
                if (Pattern.matches(it.target, superName)) pointcuts.add(it)
                else findSuperClass(aspectModifier.allClassNode[superName]?.superName)
            }
            findSuperClass(classNode.superName)
        }
        // 查找接口中的

        // 查找注解中的

        return pointcuts
    }
}