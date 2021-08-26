package com.ysj.lib.bytecodeutil.modifier

import com.android.build.api.transform.Transform
import org.objectweb.asm.tree.ClassNode

/**
 * 修改器，用于改变字节码
 *
 * @author Ysj
 * Create time: 2021/3/6
 */
interface IModifier {

    val transform: Transform

    /** 所有扫描到的 class。key：[ClassNode.name] */
    val allClassNode: Map<String, ClassNode>

    /**
     * 扫描类所有需要修改的类，每扫描到一个类会回调一次该方法
     */
    fun scan(classNode: ClassNode)

    /**
     * 开始修改 [scan] 到的类
     */
    fun modify()
}