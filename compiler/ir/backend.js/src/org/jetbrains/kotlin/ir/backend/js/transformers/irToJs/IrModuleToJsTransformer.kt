/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ModuleType
import org.jetbrains.kotlin.ir.backend.js.utils.JsGenerationContext
import org.jetbrains.kotlin.ir.backend.js.utils.Namer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.serialization.js.ModuleKind
import org.jetbrains.kotlin.utils.DFS

class IrModuleToJsTransformer(private val backendContext: JsIrBackendContext) : BaseIrElementToJsNodeTransformer<JsNode, Nothing?> {
    val moduleName = backendContext.configuration[CommonConfigurationKeys.MODULE_NAME]!!

    private fun generateModuleBody(module: IrModuleFragment, context: JsGenerationContext): List<JsStatement> {
        val statements = mutableListOf<JsStatement>()

        // TODO: fix it up with new name generator
        val anyName = context.getNameForSymbol(backendContext.irBuiltIns.anyClass)
        val throwableName = context.getNameForSymbol(backendContext.irBuiltIns.throwableClass)

        statements += JsVars(JsVars.JsVar(anyName, Namer.JS_OBJECT))
        statements += JsVars(JsVars.JsVar(throwableName, Namer.JS_ERROR))

        val preDeclarationBlock = JsBlock()
        val postDeclarationBlock = JsBlock()

        statements += preDeclarationBlock

        module.files.forEach {
            statements.add(it.accept(IrFileToJsTransformer(), context))
        }

        // sort member forwarding code
        processClassModels(context.staticContext.classModels, preDeclarationBlock, postDeclarationBlock)

        statements += postDeclarationBlock
        statements += context.staticContext.initializerBlock

        return statements
    }

    private fun findModuleExports(module: IrModuleFragment, context: JsGenerationContext): Map<JsNameRef, JsNameRef> {
        val publicDeclarations = module.files
            .flatMap { it.declarations }
            .filterIsInstance<IrDeclarationWithVisibility>()
            .filter { it.visibility == Visibilities.PUBLIC }
            .filter { !it.isEffectivelyExternal() }
            .filterIsInstance<IrSymbolOwner>()

        val publicDeclarationNames = publicDeclarations.map {
            JsNameRef(context.getNameForSymbol(it.symbol))
        }

        return publicDeclarationNames.associate { it to it }
    }

    private fun generateModuleInGlobalScope(module: IrModuleFragment): JsProgram {
        val program = JsProgram()
        val rootContext = JsGenerationContext(JsRootScope(program), backendContext)

        val moduleBody = generateModuleBody(module, rootContext)
        program.globalBlock.statements += moduleBody

        return program
    }

    private fun generateModule(module: IrModuleFragment): JsProgram {
        val program = JsProgram()
        val rootContext = JsGenerationContext(JsRootScope(program), backendContext)
        val rootFunction = JsFunction(program.rootScope, JsBlock(), "root function")

        val moduleBody = generateModuleBody(module, rootContext)
        val moduleExports = findModuleExports(module, rootContext)

        rootFunction.body.statements += moduleBody
        rootFunction.body.statements += JsReturn(
            JsObjectLiteral(
                moduleExports.map { (from, to) -> JsPropertyInitializer(from, to) },
                true
            )
        )

        val dependencies = listOf<String>()

        program.globalBlock.statements += ModuleWrapperTranslation.wrap(
            moduleName,
            rootFunction,
            dependencies,
            program,
            kind = ModuleKind.PLAIN
        )

        return program
    }

    override fun visitModuleFragment(declaration: IrModuleFragment, data: Nothing?): JsNode =
        when (backendContext.moduleType) {
            ModuleType.MAIN -> generateModule(declaration)
            ModuleType.SECONDARY -> JsEmpty
            ModuleType.TEST_RUNTIME -> generateModuleInGlobalScope(declaration)
        }


    private fun processClassModels(
        classModelMap: Map<JsName, JsClassModel>,
        preDeclarationBlock: JsBlock,
        postDeclarationBlock: JsBlock
    ) {
        val declarationHandler = object : DFS.AbstractNodeHandler<JsName, Unit>() {
            override fun result() {}
            override fun afterChildren(current: JsName) {
                classModelMap[current]?.let {
                    preDeclarationBlock.statements += it.preDeclarationBlock.statements
                    postDeclarationBlock.statements += it.postDeclarationBlock.statements
                }
            }
        }

        DFS.dfs(classModelMap.keys, {
            val neighbors = mutableListOf<JsName>()
            classModelMap[it]?.run {
                if (superName != null) neighbors += superName!!
                neighbors += interfaces
            }
            neighbors
        }, declarationHandler)
    }
}