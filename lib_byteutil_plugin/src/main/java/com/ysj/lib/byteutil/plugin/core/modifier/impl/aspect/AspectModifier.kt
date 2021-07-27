package com.ysj.lib.byteutil.plugin.core.modifier.impl.aspect

import com.android.build.api.transform.Transform
import com.ysj.lib.byteutil.api.aspect.*
import com.ysj.lib.byteutil.plugin.core.logger.YLogger
import com.ysj.lib.byteutil.plugin.core.modifier.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

/**
 * 用于处理 [Aspect] , [Pointcut] 来实现切面的修改器
 *
 * @author Ysj
 * Create time: 2021/3/6
 */
class AspectModifier(
    override val transform: Transform,
    override val allClassNode: Map<String, ClassNode>,
) : IModifier {

    private val logger = YLogger.getLogger(javaClass)

    private val targetCallStart by lazy { HashSet<PointcutBean>() }

    private val targetClass by lazy { HashSet<PointcutBean>() }

    private val targetSuperClass by lazy { HashSet<PointcutBean>() }

    private val targetInterface by lazy { HashSet<PointcutBean>() }

    private val targetAnnotation by lazy { HashSet<PointcutBean>() }

    override fun scan(classNode: ClassNode) {
        // 过滤所有没有 Aspect 注解的类
        if (Type.getDescriptor(Aspect::class.java) !in classNode.invisibleAnnotations?.map { it.desc } ?: Collections.EMPTY_LIST) return
        classNode.methods.forEach {
            // 过滤构造器和静态代码块
            if (it.name == "<init>" || it.name == "<clinit>") return@forEach
            // 查找 Pointcut 注解的方法
            val pointCutAnnotation = it.invisibleAnnotations
                ?.find { anode -> anode.desc == Type.getDescriptor(Pointcut::class.java) }
                ?: return@forEach
            // 收集 Pointcut 注解的参数
            val params = pointCutAnnotation.params()
            val orgTarget = params[Pointcut::target.name] as String
            val target = orgTarget.substringAfter(":")
            val targetType = orgTarget.substringBefore(":")
            val collection = when (targetType) {
                "class" -> targetClass
                "superClass" -> targetSuperClass
                "interface" -> targetInterface
                "annotation" -> targetAnnotation
                else -> throw RuntimeException("${Pointcut::class.java.simpleName} 中 target 前缀不合法：${orgTarget}")
            }
            val pointcutBean = PointcutBean(
                aspectClassName = classNode.name,
                aspectFunName = it.name,
                aspectFunDesc = it.desc,
                target = target,
                targetType = targetType,
                funName = params[Pointcut::funName.name] as String,
                funDesc = params[Pointcut::funDesc.name] as String,
                position = params[Pointcut::position.name] as Int,
            ).also { pcb ->
                logger.verbose("====== method: ${it.name} ======\n$pcb")
            }
            // 检查方法参数是否合法
            Type.getArgumentTypes(it.desc).forEach checkArgs@{ type ->
                val typeClass = type.className
                when (val p = pointcutBean.position) {
                    POSITION_START, POSITION_RETURN -> if (typeClass != JoinPoint::class.java.name) {
                        throw RuntimeException(
                            """
                            检测到 ${classNode.name} 中方法 ${it.name} ${it.desc} 的参数不合法
                            该 position: $p 支持的参数为：
                            1. ${JoinPoint::class.java.simpleName}
                            2. 无参数
                            """.trimIndent()
                        )
                    }
                    POSITION_CALL -> {
                        if (typeClass == JoinPoint::class.java.name) return@checkArgs
                        if (typeClass == CallingPoint::class.java.name) return@checkArgs
                        throw RuntimeException(
                            """
                            检测到 ${classNode.name} 中方法 ${it.name} ${it.desc} 的参数不合法
                            该 position: $p 支持的参数为：
                            1. ${JoinPoint::class.java.name}
                            2. ${CallingPoint::class.java.name}
                            3. 无参数
                            """.trimIndent()
                        )
                    }
                }
            }
            when (pointcutBean.position) {
                POSITION_START, POSITION_RETURN -> collection.add(pointcutBean)
                POSITION_CALL -> targetCallStart.add(pointcutBean)
            }
        }
    }

    override fun modify(classNode: ClassNode) {
        handleAspect(classNode)
        handlePointcut(classNode)
    }

    /**
     * 在 [Aspect] 注解的类中添加用于获取该类实例的静态成员
     */
    private fun handleAspect(classNode: ClassNode) {
        if (Type.getDescriptor(Aspect::class.java) !in classNode.invisibleAnnotations?.map { it.desc } ?: Collections.EMPTY_LIST) return
        val fieldInstance = FieldNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "instance",
            Type.getObjectType(classNode.name).descriptor,
            null,
            null
        )
        classNode.fields.add(fieldInstance)
        var clint = classNode.methods.find { it.name == "<clinit>" && it.desc == "()V" }
        if (clint == null) {
            clint = MethodNode(
                Opcodes.ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null
            )
            classNode.methods.add(0, clint)
            // 刚创建出来的 instructions 是空的，必需要添加个 return
            clint.instructions.add(InsnNode(Opcodes.RETURN))
        }
        clint.instructions.insertBefore(clint.instructions.first, InsnList().apply {
            add(TypeInsnNode(Opcodes.NEW, classNode.name))
            add(InsnNode(Opcodes.DUP))
            add(MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                classNode.name,
                "<init>",
                "()V",
                false
            ))
            add(FieldInsnNode(
                Opcodes.PUTSTATIC,
                classNode.name,
                fieldInstance.name,
                fieldInstance.desc
            ))
        })
    }

    /**
     * 处理 [Pointcut] 收集的信息
     */
    private fun handlePointcut(classNode: ClassNode) {
        val targetClassPointcuts = findPointcuts(classNode)
        classNode.methods.forEach { methodNode ->
            val jointPointInsn = handleMethodCall(classNode, methodNode)
            targetClassPointcuts.forEach targetClass@{ pointcut ->
                if (!Pattern.matches(pointcut.funName, methodNode.name)) return@targetClass
                if (!Pattern.matches(pointcut.funDesc, methodNode.desc)) return@targetClass
                pointcut.handleMethodInner(classNode, methodNode, jointPointInsn)
            }
        }
    }

    private fun handleMethodCall(
        classNode: ClassNode,
        methodNode: MethodNode,
    ): JointPointInsn? {
        val insnList = methodNode.instructions
        val insnNodes = insnList.toArray()
        var jointPoint: JointPointInsn? = null
        insnNodes.forEach node@{ node ->
            if (node !is MethodInsnNode) return@node
            val pointcutBean = targetCallStart.find {
                Pattern.matches(it.target, node.owner)
                        && Pattern.matches(it.funName, node.name)
                        && Pattern.matches(it.funDesc, node.desc)
            } ?: return@node
            val firstLabel = insnNodes.find { it is LabelNode } ?: return@node
            // 切面方法的参数
            val aspectFunArgs = Type.getArgumentTypes(pointcutBean.aspectFunDesc)
            if (aspectFunArgs.isNotEmpty()) {
                jointPoint = jointPoint ?: jointPoint(methodNode, insnNodes).apply {
                    insnList.insertBefore(firstLabel, nodes)
                }
            }
            insnList.insertBefore(node, InsnList().apply {
                add(FieldInsnNode(
                    Opcodes.GETSTATIC,
                    pointcutBean.aspectClassName,
                    "instance",
                    Type.getObjectType(pointcutBean.aspectClassName).descriptor
                ))
                if (aspectFunArgs.isNotEmpty() && jointPoint != null) {
                    add(VarInsnNode(Opcodes.ALOAD, jointPoint!!.localVarIndex))
                }
                add(MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    pointcutBean.aspectClassName,
                    pointcutBean.aspectFunName,
                    pointcutBean.aspectFunDesc,
                    false
                ))
            })
            logger.info("Method Call 插入 --> ${classNode.name}#${methodNode.name}${methodNode.desc}：${node.opcode} ${node.owner} ${node.name} ${node.desc}")
        }
        return jointPoint
    }

    private fun PointcutBean.handleMethodInner(
        classNode: ClassNode,
        methodNode: MethodNode,
        initJointPoint: JointPointInsn?,
    ) {
        if (position != POSITION_RETURN && position != POSITION_START) return
        val insnList = methodNode.instructions
        val firstLabel = if (methodNode.name != "<init>") insnList.first else {
            var result: AbstractInsnNode? = null
            insnList.iterator().forEach { if (it.opcode == Opcodes.INVOKESPECIAL) result = it.next }
            result
        } ?: return
        // 切面方法的参数
        val hasArg = Type.getArgumentTypes(aspectFunDesc).isNotEmpty()
        val jointPoint = if (!hasArg) null else {
            initJointPoint ?: jointPoint(methodNode).apply {
                insnList.insertBefore(firstLabel, nodes)
            }
        }
        // 将 Pointcut 和 JointPoint 连接 XXX.instance.xxxfun(jointPoint);
        val callAspectFun: (AbstractInsnNode) -> Unit = {
            insnList.insertBefore(it, InsnList().apply {
                add(FieldInsnNode(
                    Opcodes.GETSTATIC,
                    aspectClassName,
                    "instance",
                    Type.getObjectType(aspectClassName).descriptor
                ))
                if (jointPoint != null) add(VarInsnNode(Opcodes.ALOAD, jointPoint.localVarIndex))
                add(MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    aspectClassName,
                    aspectFunName,
                    aspectFunDesc,
                    false
                ))
            })
        }
        when (position) {
            POSITION_START -> callAspectFun(firstLabel)
            POSITION_RETURN -> for (insnNode in insnList) {
                if (insnNode.opcode !in Opcodes.IRETURN..Opcodes.RETURN) continue
                callAspectFun(insnNode)
            }
        }
        logger.info("Method Inner 插入 --> ${classNode.name}#${methodNode.name}${methodNode.desc}")
    }

    private fun jointPoint(methodNode: MethodNode): JointPointInsn {
        var localVarIndex: Int
        val insn = InsnList().apply {
            // 将方法中的参数存到数组中 Object[] args = {arg1, arg2, arg3, ...};
            val argsInsnList = methodNode.argsInsnList()
            localVarIndex = (argsInsnList.last as VarInsnNode).`var`
            add(argsInsnList)
            // 构建 JointPoint 实体 JointPoint jointPoint = new JointPoint(this, args);
            add(newObject(
                JoinPoint::class.java, linkedMapOf(
                    Any::class.java to InsnList().apply {
                        add(VarInsnNode(Opcodes.ALOAD, 0))
                    },
                    Array<Any?>::class.java to InsnList().apply {
                        add(VarInsnNode(Opcodes.ALOAD, localVarIndex))
                    },
                )
            ))
            add(VarInsnNode(Opcodes.ASTORE, ++localVarIndex))
        }
        // 将原始方法体中所有非(方法参数列表的本地变量索引)的索引增加插入的本地变量(jointPoint)所占的索引大小
        val i = localVarIndex - 1
        methodNode.instructions.iterator().forEach fixEach@{
            if (it is IincInsnNode && it.`var` >= i) it.`var` += 2
            if (it is VarInsnNode && it.`var` >= i) it.`var` += 2
        }
        return JointPointInsn(localVarIndex, insn)
    }

    /**
     * 查找类中所有的切入点
     */
    private fun findPointcuts(classNode: ClassNode): ArrayList<PointcutBean> {
        val pointcuts = ArrayList<PointcutBean>()
        // 查找类中的
        targetClass.forEach { if (Pattern.matches(it.target, classNode.name)) pointcuts.add(it) }
        // 查找父类中的
        for (it in targetSuperClass) {
            fun findSuperClass(superName: String?) {
                superName ?: return
                if (Pattern.matches(it.target, superName)) pointcuts.add(it)
                else findSuperClass(allClassNode[superName]?.superName)
            }
            findSuperClass(classNode.superName)
        }
        // 查找接口中的

        // 查找注解中的

        return pointcuts
    }

    class JointPointInsn(
        /** jointPoint 变量索引 */
        val localVarIndex: Int,
        val nodes: InsnList,
    )
}