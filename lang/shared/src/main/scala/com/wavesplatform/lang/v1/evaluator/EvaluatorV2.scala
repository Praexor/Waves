package com.wavesplatform.lang.v1.evaluator

import cats.Id
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.lang.directives.values.StdLibVersion
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms._
import com.wavesplatform.lang.v1.evaluator.Contextful.NoContext
import com.wavesplatform.lang.v1.evaluator.ctx.{BaseFunction, NativeFunction, UserFunction}

class EvaluatorV2(limit: Int, stdLibVersion: StdLibVersion) {
  case class Context(
    lets: Map[String, (EXPR, Context, Boolean)] = Map(),
    functions: Map[FunctionHeader, BaseFunction[NoContext]] = Map(),
    cost: Int = 0
  ) {
    def isExhausted: Boolean =
      cost >= limit

    def withCost(addCost: Int): Context =
      copy(cost = cost + addCost)

    def withLet(newLet: LET, isEvaluated: Boolean): Context =
      copy(lets = lets + (newLet.name -> (newLet.value, this, isEvaluated)))

    def withFunction(function: UserFunction[NoContext]): Context =
      copy(functions = functions + (function.header -> function))

    def combine(that: Context): Context =
      Context(
        lets ++ that.lets,
        functions ++ that.functions,
        Math.max(cost, that.cost)
      )
  }

  def root(expr: EXPR, ctx: Context): (EXPR, Context) =
    if (ctx.isExhausted)
      (expr, ctx)
    else
      expr match {
        case LET_BLOCK(let, body)          => evaluateLetBlock(let, body, ctx)
        case BLOCK(let: LET, body)         => evaluateLetBlock(let, body, ctx)
        case BLOCK(func: FUNC, body)       => evaluateFunctionBlock(func, body, ctx)
        case GETTER(obj, field)            => evaluateGetter(obj, field, ctx)
        case IF(cond, ifTrue, ifFalse)     => evaluateIfBlock(cond, ifTrue, ifFalse, ctx)
        case FUNCTION_CALL(function, args) => evaluateFunctionCall(function, args, ctx)
        case REF(key)                      => evaluateRef(key, ctx)
        case e: EVALUATED                  => evaluated(e, ctx)
      }

  // todo check all local {} blocks semantics from EvaluatorV1
  private def evaluated(e: EVALUATED, ctx: Context): (EXPR, Context) =
    (e, ctx)

  private def evaluateLetBlock(let: LET, nextExpr: EXPR, ctx: Context): (EXPR, Context) = {
    val (nextExprResult, nextExprCtx) = root(nextExpr, ctx.withLet(let, isEvaluated = false))
    val resultExpr =
      if (nextExprResult.isInstanceOf[EVALUATED])
        nextExprResult
      else {
        val letDecl = LET(let.name, nextExprCtx.lets(let.name)._1)
        BLOCK(letDecl, nextExprResult)
      }
    (resultExpr, nextExprCtx)
  }

  private def evaluateFunctionBlock(funcDef: FUNC, nextExpr: EXPR, ctx: Context): (EXPR, Context) = {
    val function = UserFunction[NoContext](funcDef.name, 0, null, funcDef.args.map(n => (n, null)): _*)(funcDef.body)
    val (nextExprResult, nextExprCtx) = root(nextExpr, ctx.withFunction(function))
    val resultExpr =
      if (nextExprResult.isInstanceOf[EVALUATED]) nextExprResult
      else BLOCK(funcDef, nextExprResult)
    (resultExpr, nextExprCtx)
  }

  private def evaluateRef(key: String, ctx: Context): (EXPR, Context) = {
    val (letExpr, letCtx, isEvaluated) = ctx.lets(key)
    if (isEvaluated)
      (letExpr, ctx.withCost(1))
    else {
      val (letValue, resultCtx) = root(letExpr, letCtx combine ctx)
      if (resultCtx.isExhausted)
        (REF(key), resultCtx.withLet(LET(key, letValue), isEvaluated = false))
      else
        (letValue, resultCtx.withLet(LET(key, letValue), isEvaluated = true).withCost(1))
    }
  }

  private def evaluateFunctionCall(header: FunctionHeader, args: List[EXPR], ctx: Context): (EXPR, Context) = {
    val function = ctx.functions(header)
    val (resultCtx, evaluatedArgs) = evaluateFunctionArgs(args, ctx)
    if (resultCtx.isExhausted) {
      val call = FUNCTION_CALL(header, evaluatedArgs ::: args.drop(evaluatedArgs.size))
      (call, resultCtx)
    }
    else
      evaluateFunctionBody(function, resultCtx, evaluatedArgs.asInstanceOf[List[EVALUATED]]) //todo type safety
  }

  private def evaluateFunctionArgs(args: List[EXPR], ctx: Context): (Context, List[EXPR]) =
    args.foldLeft((ctx, List.empty[EXPR])) {
      case ((currentCtx, evaluatedArgs), nextArgExpr) =>
        if (currentCtx.isExhausted)
          (currentCtx, evaluatedArgs)
        else {
          val (evaluatedArg, newCtx) = root(nextArgExpr, currentCtx)
          (newCtx, evaluatedArgs :+ evaluatedArg)                        // todo optimize?
        }
    }

  private def evaluateFunctionBody(
    function: BaseFunction[NoContext],
    ctx: Context,
    evaluatedArgs: List[EVALUATED]
  ): (EXPR, Context) =
    function match {
      case NativeFunction(_, costByVersion, _, ev, _) =>
        val cost = costByVersion(stdLibVersion).toInt
        if (ctx.cost + cost > limit)
          (FUNCTION_CALL(function.header, evaluatedArgs), ctx)  //todo test
        else {
          val result = ev[Id]((Contextful.empty, evaluatedArgs)).explicitGet()
          (result, ctx.withCost(cost))
        }
      case UserFunction(_, _, _, signature, expr, _) =>
        val argsWithExpr =
          (signature.args zip evaluatedArgs)
            .foldRight(expr[Id](Contextful.empty)) {
              case (((argName, _), argValue), argsWithExpr) => //todo maybe check for existence
                BLOCK(LET(argName, argValue), argsWithExpr)
            }
        val (result, functionResultCtx) = root(argsWithExpr, ctx)
        (result, ctx.copy(cost = functionResultCtx.cost))
    }

  private def evaluateIfBlock(cond: EXPR, ifTrue: EXPR, ifFalse: EXPR, ctx: Context): (EXPR, Context) = {
    val (condResult, condResultCtx) = root(cond, ctx)
    if (condResultCtx.isExhausted)
      (IF(condResult, ifTrue, ifFalse), condResultCtx)
    else
      condResult match {
        case TRUE  => root(ifTrue,  condResultCtx.withCost(1))
        case FALSE => root(ifFalse, condResultCtx.withCost(1))
        case _     => ???                                       // todo ???
      }
  }

  private def evaluateGetter(obj: EXPR, field: String, ctx: Context): (EVALUATED, Context) = {
    val (exprResult, exprResultCtx) = root(obj, ctx)
    val fields = exprResult.asInstanceOf[CaseObj].fields
    (fields(field), exprResultCtx.withCost(1))                            // todo handle absence
  }
}
