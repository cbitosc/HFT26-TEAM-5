package app.getknit.knit.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.isAncestor

/**
 * A coroutine whose whole job is an endless `while` loop that ticks on `delay`, with nothing in the round to catch a
 * throw: `scope.launch { while (true) { delay(interval); round() } }`. The first exception out of a round ends the
 * coroutine, and on a `SupervisorJob` scope nothing notices: the scope's handler logs one line and the loop is gone
 * until the scope is rebuilt (#206, where MeshManager's 60 s re-offer and forward-store sweep could stop for a whole
 * mesh session). Run the round through `everyRound` (`mesh/MeshCoroutines.kt`), or catch inside the round.
 *
 * Matched on the tree alone, since detekt runs here without type resolution:
 * - the loop is a statement of a lambda passed straight to `launch` or `async`;
 * - its condition is `true` or ends in `isActive`;
 * - a statement of its body is a `delay(…)` call;
 * - nothing in it leaves the loop (a `break` or a `return` that targets it or something around it), so it is a ticker,
 *   not a wait;
 * - at least one other statement of the body is not a `try` with a `catch`, nor a chain that starts with a call to
 *   one of [guards] (`runCatching`, and whatever helpers the config names, such as `runRound`).
 */
class BarePeriodicLoop(
    config: Config,
) : Rule(
        config,
        "A launched endless loop that ticks on delay ends at its round's first exception. Run the round through " +
            "everyRound (mesh/MeshCoroutines.kt), or catch inside it.",
    ) {
    @Configuration("Calls that run their block and catch what it throws: a statement that is one of them guards its round.")
    private val guards: List<String> by config(listOf(RUN_CATCHING))

    override fun visitWhileExpression(expression: KtWhileExpression) {
        super.visitWhileExpression(expression)
        val body = statementsOf(expression.body) ?: return
        val ticks = body.filter(::isDelayCall)
        if (!endless(expression.condition) || ticks.isEmpty() || !launchedDirectly(expression) || leaves(expression)) return
        val round = body - ticks.toSet()
        if (round.isEmpty() || round.all(::guarded)) return
        report(
            Finding(
                Entity.from(expression),
                "This loop is the whole of a launched coroutine, ticks on delay and catches nothing: the first exception " +
                    "out of a round ends it for good (#206). Run the round through everyRound (mesh/MeshCoroutines.kt), or " +
                    "catch inside the round.",
            ),
        )
    }

    private fun statementsOf(body: KtExpression?): List<KtExpression>? =
        when (body) {
            null -> null
            is KtBlockExpression -> body.statements
            else -> listOf(body)
        }

    private fun endless(condition: KtExpression?): Boolean =
        when (condition) {
            is KtConstantExpression -> condition.text == "true"
            is KtNameReferenceExpression -> condition.getReferencedName() == IS_ACTIVE
            is KtDotQualifiedExpression -> (condition.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == IS_ACTIVE
            else -> false
        }

    private fun isDelayCall(statement: KtExpression): Boolean {
        val call =
            when (statement) {
                is KtCallExpression -> statement
                is KtDotQualifiedExpression -> statement.selectorExpression as? KtCallExpression
                else -> null
            }
        return call?.calleeExpression?.text == DELAY
    }

    /** The loop sits in the block of a lambda handed straight to `launch` / `async`, trailing or in parentheses. */
    private fun launchedDirectly(loop: KtWhileExpression): Boolean {
        val statement = loop.parent as? KtLabeledExpression ?: loop
        val block = statement.parent as? KtBlockExpression ?: return false
        val literal = block.parent as? KtFunctionLiteral ?: return false
        return calleeOf(literal) in LAUNCHERS
    }

    /** The name of the call [literal] is passed to as an argument, or null when it is not an argument. */
    private fun calleeOf(literal: KtFunctionLiteral): String? {
        val lambda = literal.parent as? KtLambdaExpression ?: return null
        val argument = (lambda.parent as? KtLabeledExpression)?.parent ?: lambda.parent
        if (argument !is KtValueArgument) return null
        return argument.getParentOfType<KtCallExpression>(true)?.calleeExpression?.text
    }

    /** Whether a `break` or a `return` in the body takes control out of [loop]. */
    private fun leaves(loop: KtWhileExpression): Boolean {
        val label = (loop.parent as? KtLabeledExpression)?.getLabelName()
        val breaks =
            loop.collectDescendantsOfType<KtBreakExpression>().any { brk ->
                val target = brk.getLabelName()
                if (target != null) target == label else brk.getParentOfType<KtLoopExpression>(true) == loop
            }
        return breaks || loop.collectDescendantsOfType<KtReturnExpression>().any { leavesThrough(it, loop) }
    }

    /**
     * A `return` leaves [loop] unless it ends something nested inside it: a local or anonymous function, or a lambda
     * its label names. An unlabelled one in a lambda is a non-local return, which leaves.
     */
    private fun leavesThrough(
        ret: KtReturnExpression,
        loop: KtWhileExpression,
    ): Boolean {
        if (ret.getParentOfType<KtNamedFunction>(true)?.let { loop.isAncestor(it, strict = true) } == true) return false
        val target = ret.getLabelName() ?: return true
        var literal = ret.getParentOfType<KtFunctionLiteral>(true)
        while (literal != null && loop.isAncestor(literal, strict = true)) {
            if (target in labelsOf(literal)) return false
            literal = literal.getParentOfType<KtFunctionLiteral>(true)
        }
        return true
    }

    /** The names a `return@name` may use for [literal]: its explicit label and the call it is passed to. */
    private fun labelsOf(literal: KtFunctionLiteral): Set<String> {
        val explicit = ((literal.parent as? KtLambdaExpression)?.parent as? KtLabeledExpression)?.getLabelName()
        return setOfNotNull(explicit, calleeOf(literal))
    }

    /** A `try` with at least one `catch`, or a chain that starts with a call to one of [guards]. */
    private fun guarded(statement: KtExpression): Boolean {
        if (statement is KtTryExpression) return statement.catchClauses.isNotEmpty()
        var head: KtExpression = statement
        while (head is KtDotQualifiedExpression) head = head.receiverExpression
        return (head as? KtCallExpression)?.calleeExpression?.text in guards
    }

    private companion object {
        const val IS_ACTIVE = "isActive"
        const val DELAY = "delay"
        const val RUN_CATCHING = "runCatching"
        val LAUNCHERS = setOf("launch", "async")
    }
}
