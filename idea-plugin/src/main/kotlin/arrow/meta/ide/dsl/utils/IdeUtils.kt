package arrow.meta.ide.dsl.utils

import arrow.meta.ide.IdeMetaPlugin
import arrow.meta.phases.analysis.resolveFunctionType
import arrow.meta.phases.analysis.returns
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.impl.jar.CoreJarVirtualFile
import com.intellij.openapi.vfs.local.CoreLocalVirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.FileTypeIndex
import org.celtric.kotlin.html.BlockElement
import org.celtric.kotlin.html.InlineElement
import org.celtric.kotlin.html.code
import org.celtric.kotlin.html.text
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils
import org.jetbrains.kotlin.diagnostics.PsiDiagnosticUtils
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.caches.resolve.analyzeWithAllCompilerChecks
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.fir.firResolveState
import org.jetbrains.kotlin.idea.fir.getOrBuildFir
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.search.projectScope
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.renderer.RenderingFormat
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

internal fun <A> isNotNull(a: A?): Boolean = a?.let { true } ?: false


/**
 * traverse and filters starting from the root node [receiver] down to all it's children and applying [f]
 */
fun <A : PsiElement, B : Any> PsiElement.traverseFilter(on: Class<A>, f: (A) -> B?): List<B> =
  SyntaxTraverser.psiTraverser(this).filter(on).mapNotNull(f).toList()

/**
 * a convenient function that collects all child nodes [A] starting from [receiver]
 * it applies [traverseFilter] with the identity function
 */
fun <A : PsiElement> PsiElement.sequence(on: Class<A>): List<A> =
  traverseFilter(on) { it }

/**
 * collects all call-sites
 */
val KtElement.callElements: List<KtCallElement>
  get() = sequence(KtCallElement::class.java)

val KtElement.typeReferences: List<KtTypeReference>
  get() = sequence(KtTypeReference::class.java)

val KtElement.typeProjections: List<KtTypeProjection>
  get() = sequence(KtTypeProjection::class.java)

val KtCallElement.returnType: KotlinType?
  get() = resolveToCall()?.resultingDescriptor?.returnType

/**
 * returns all return types of each call-site starting from the receiver
 */
val KtElement.callReturnTypes: List<KotlinType>
  get() = traverseFilter(KtCallElement::class.java) { it.returnType }

/**
 * traversal of depth 1 on returnTypes in Function [ktFunction] and all it's calls in the body
 * [f] defines on what property two Types are equal
 * TODO: add returns with a traversal of depth n by virtue of recursion
 */
@Suppress("UNCHECKED_CAST")
fun <F : CallableDescriptor, A> F.returns(
  f: (KotlinType) -> A = { it as A },
  ktFunction: KtNamedFunction,
  types: KotlinBuiltIns.() -> List<KotlinType>
): Boolean =
  returns(f, types) || returns(f, ktFunction.callReturnTypes, types)


/**
 * traversal of depth 1 on returnTypes in Property [prop] and all it's calls in the body
 * [f] defines on what property two Types are equal
 * TODO: add returns with a traversal of depth n by virtue of recursion
 */
@Suppress("UNCHECKED_CAST")
fun <F : CallableDescriptor, A> F.returns(
  f: (KotlinType) -> A = { it as A },
  prop: KtProperty,
  types: KotlinBuiltIns.() -> List<KotlinType>
): Boolean =
  returns(f, types) || returns(f, prop.callReturnTypes, types)

/**
 * convenience function where [f] reduces FunctionReturnTypes of subsequent calls in the initializer to their returnType
 */
fun <F : CallableDescriptor> F.returns(prop: KtProperty, types: KotlinBuiltIns.() -> List<KotlinType>): Boolean =
  returns(resolveFunctionType, prop, types)

/**
 * convenience function where [f] reduces FunctionReturnTypes of subsequent calls in the body to their returnType
 */
fun <F : CallableDescriptor> F.returns(ktFunction: KtNamedFunction, types: KotlinBuiltIns.() -> List<KotlinType>): Boolean =
  returns(resolveFunctionType, ktFunction, types)

/**
 * reified PsiElement replacement
 */
inline fun <reified K : PsiElement> K.replaceK(to: K): K? =
  replace(to).safeAs()

// fixme use ViewProvider's files instead?
@Suppress("UNCHECKED_CAST")
fun <F : PsiFile> List<VirtualFile>.files(project: Project): List<F> =
  mapNotNull { PsiManager.getInstance(project).findFile(it) as? F }

fun Project.ktFiles(): List<VirtualFile> =
  FileTypeIndex.getFiles(KotlinFileType.INSTANCE, projectScope()).filterNotNull()

fun VirtualFile.quoteRelevantFile(): Boolean =
  isValid &&
    this.fileType is KotlinFileType &&
    (isInLocalFileSystem || ApplicationManager.getApplication().isUnitTestMode)

/**
 * Collects all Kotlin files of the current project which are source files for Quote transformations.
 */
fun Project.quoteRelevantFiles(): List<KtFile> =
  ktFiles()
    .filter { it.quoteRelevantFile() && it.isInLocalFileSystem }
    .files(this)

/**
 * returns the [DeclarationDescriptor]s of each File
 */
fun KtFile.resolve(facade: ResolutionFacade, resolveMode: BodyResolveMode = BodyResolveMode.PARTIAL): Pair<KtFile, List<DeclarationDescriptor>> =
  this to declarations.map { facade.resolveToDescriptor(it, resolveMode) }

/**
 * returns the [BindingContext] for a successfully resolved file after Analysis
 */
internal val KtFile.ctx: BindingContext?
  get() = analyzeWithAllCompilerChecks()
    .takeIf { !it.isError() }?.bindingContext

/**
 * Renders descriptors with the specified options
 */
internal val IdeMetaPlugin.descriptorRender: DescriptorRenderer
  get() = DescriptorRenderer.COMPACT_WITH_SHORT_TYPES.withOptions {
    textFormat = RenderingFormat.HTML
    classifierNamePolicy = classifierNamePolicy()
    unitReturnType = true
  }

internal val DescriptorRenderer.Companion.`br`: String
  get() = "<br/>"

/**
 * this extension is an instance of the generalized version [getOrBuildFir]
 */
fun KtCallableDeclaration.toFir(phase: FirResolvePhase = FirResolvePhase.BODY_RESOLVE): FirCallableDeclaration<*> =
  getOrBuildFir(firResolveState(), phase)

val Project.ktPsiFactory: KtPsiFactory
  get() = KtPsiFactory(this)

fun <A> List<A?>.toNotNullable(): List<A> = fold(emptyList()) { acc: List<A>, r: A? -> if (r != null) acc + r else acc }

val <K : KtElement> arrow.meta.quotes.Scope<K>.path: CompilerMessageLocation?
  get() = value?.run {
    containingFile?.let {
      psiFileToMessageLocation(it, "<no path>", DiagnosticUtils.getLineAndColumnInPsiFile(it, textRange))
    }
  }

fun psiFileToMessageLocation(
  file: PsiFile,
  defaultValue: String?,
  lineAndColumn: PsiDiagnosticUtils.LineAndColumn
): CompilerMessageLocation? {
  val virtualFile = file.virtualFile
  val path = (if (virtualFile != null) virtualFileToPath(virtualFile) else defaultValue)!!
  return CompilerMessageLocation.create(path, lineAndColumn.line, lineAndColumn.column, lineAndColumn.lineContent)
}

fun virtualFileToPath(virtualFile: VirtualFile): String =
  if (virtualFile is CoreLocalVirtualFile || virtualFile is CoreJarVirtualFile) {
    FileUtil.toSystemDependentName(virtualFile.path)
  } else virtualFile.path


fun <A> kotlin(a: A): InlineElement = code(other = mapOf("lang" to "kotlin")) { "\t$a\n" }
fun kotlin(a: String): InlineElement = code(other = mapOf("lang" to "kotlin")) { "\t${text(a).content}\n" }
fun <A> h1(a: A): BlockElement = org.celtric.kotlin.html.h1("$a")
fun <A> code(a: A): InlineElement = code("\n\t$a\n")
