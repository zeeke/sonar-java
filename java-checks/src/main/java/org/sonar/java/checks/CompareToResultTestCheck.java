/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import org.sonar.check.BelongsToProfile;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.model.expression.MethodInvocationTreeImpl;
import org.sonar.java.resolve.Symbol;
import org.sonar.java.resolve.Symbol.TypeSymbol;
import org.sonar.java.resolve.Type.ClassType;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.Tree.Kind;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;
import org.sonar.plugins.java.api.tree.VariableTree;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Rule(
  key = "S2200",
  priority = Priority.MAJOR,
  tags = {"bug"})
@BelongsToProfile(title = "Sonar way", priority = Priority.MAJOR)
public class CompareToResultTestCheck extends SubscriptionBaseVisitor {

  @Override
  public List<Kind> nodesToVisit() {
    return ImmutableList.of(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO);
  }

  @Override
  public void visitNode(Tree tree) {
    BinaryExpressionTree binaryExpression = (BinaryExpressionTree) tree;
    if (isInvalidTest(binaryExpression.leftOperand(), binaryExpression.rightOperand())) {
      addIssue(tree, "Only the sign of the result should be examined.");
    }
  }

  private boolean isInvalidTest(ExpressionTree operand1, ExpressionTree operand2) {
    return isNonZeroInt(operand1) && isCompareToResult(operand2)
      || isNonZeroInt(operand2) && isCompareToResult(operand1);
  }

  private boolean isCompareToResult(ExpressionTree expression) {
    if (hasSemantic()) {
      if (expression.is(Tree.Kind.METHOD_INVOCATION)) {
        return isCompareToInvocation((MethodInvocationTreeImpl) expression);
      }
      if (expression.is(Tree.Kind.IDENTIFIER)) {
        return isIdentifierContainingCompareToResult((IdentifierTree) expression);
      }
    }
    return false;
  }

  private boolean isCompareToInvocation(MethodInvocationTreeImpl invocation) {
    Symbol method = invocation.getSymbol();
    if ("compareTo".equals(method.getName()) && invocation.arguments().size() == 1) {
      TypeSymbol methodOwner = method.owner().enclosingClass();
      Set<ClassType> superTypes = methodOwner.superTypes();
      for (ClassType classType : superTypes) {
        if (classType.is("java.lang.Comparable")) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isIdentifierContainingCompareToResult(IdentifierTree identifier) {
    Symbol variableSymbol = getSemanticModel().getReference(identifier);
    if (variableSymbol == null) {
      return false;
    }
    VariableTree variableDefinition = (VariableTree) getSemanticModel().getTree(variableSymbol);
    if (variableDefinition != null) {
      ExpressionTree initializer = variableDefinition.initializer();
      if (initializer != null && initializer.is(Tree.Kind.METHOD_INVOCATION) && variableSymbol.owner().isKind(Symbol.MTH)) {
        Tree method = getSemanticModel().getTree(variableSymbol.owner());
        return isCompareToInvocation((MethodInvocationTreeImpl) initializer) && !isReassigned(variableSymbol, method);
      }
    }
    return false;
  }

  private boolean isNonZeroInt(ExpressionTree expression) {
    return isNonZeroIntLiteral(expression)
      || expression.is(Tree.Kind.UNARY_MINUS) && isNonZeroIntLiteral(((UnaryExpressionTree) expression).expression());
  }

  private boolean isNonZeroIntLiteral(ExpressionTree expression) {
    return expression.is(Tree.Kind.INT_LITERAL) && !"0".equals(((LiteralTree) expression).value());
  }
  
  private boolean isReassigned(Symbol variableSymbol, Tree method) {
    Collection<IdentifierTree> usages = getSemanticModel().getUsages(variableSymbol);
    ReAssignmentFinder reAssignmentFinder = new ReAssignmentFinder(usages);
    method.accept(reAssignmentFinder);
    return reAssignmentFinder.foundReAssignment;
  }

  private static class ReAssignmentFinder extends BaseTreeVisitor {

    private final Collection<IdentifierTree> usages;
    private boolean foundReAssignment = false;

    public ReAssignmentFinder(Collection<IdentifierTree> usages) {
      this.usages = usages;
    }

    @Override
    public void visitUnaryExpression(UnaryExpressionTree unaryExp) {
      if (unaryExp.is(Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT, Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT)) {
        checkReAssignment(unaryExp.expression());
      }
      super.visitUnaryExpression(unaryExp);
    }

    @Override
    public void visitAssignmentExpression(AssignmentExpressionTree assignmentExpression) {
      checkReAssignment(assignmentExpression.variable());
      super.visitAssignmentExpression(assignmentExpression);
    }

    private void checkReAssignment(ExpressionTree expression) {
      if (expression.is(Tree.Kind.IDENTIFIER)) {
        IdentifierTree identifier = (IdentifierTree) expression;
        if (usages.contains(identifier)) {
          foundReAssignment = true;
        }
      }
    }
  }

}
