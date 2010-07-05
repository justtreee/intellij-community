package com.jetbrains.python.inspections;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.util.Processor;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.actions.RenameParameterQuickFix;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyQualifiedName;
import com.jetbrains.python.psi.types.PyClassType;
import com.jetbrains.python.psi.types.PyNoneType;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks that arguments to property() and @property and friends are ok.
 * <br/>
 * User: dcheryasov
 * Date: Jun 30, 2010 2:53:05 PM
 */
public class PyPropertyDefinitionInspection extends PyInspection {

  @Nls
  @NotNull
  public String getDisplayName() {
    return PyBundle.message("INSP.NAME.property.definition");
  }

  LanguageLevel myLevel = null;
  List<PyClass> myStringClasses = new ArrayList<PyClass>(2);
  PyParameterList myOneParamList; // arglist with one arg, 'self'
  PyParameterList myTwoParamList; // arglist with two args, 'self' and 'value'

  @Override
  public void inspectionStarted(LocalInspectionToolSession session) {
    super.inspectionStarted(session);
    // save us continuous checks for level, module, stc
    myLevel = null;
    final PsiFile psifile = session.getFile();
    if (psifile != null) {
      VirtualFile vfile = psifile.getVirtualFile();
      if (vfile != null) myLevel = LanguageLevel.forFile(vfile);
    }
    if (myLevel == null) myLevel = LanguageLevel.getDefault();
    // string classes
    myStringClasses.clear();
    final PyBuiltinCache builtins = PyBuiltinCache.getInstance(psifile);
    PyClass cls = builtins.getClass("str");
    if (cls != null) myStringClasses.add(cls);
    cls = builtins.getClass("unicode");
    if (cls != null) myStringClasses.add(cls);
    // reference signatures
    PyClass object_class = builtins.getClass("object");
    if (object_class != null) {
      final PyFunction method_repr = object_class.findMethodByName("__repr__", false);
      if (method_repr != null) myOneParamList = method_repr.getParameterList();
      final PyFunction method_delattr = object_class.findMethodByName("__delattr__", false);
      if (method_delattr != null) myTwoParamList = method_delattr.getParameterList();
    }
  }

  private static final List<String> SUFFIXES = new ArrayList<String>(2);
  static {
    SUFFIXES.add("setter");
    SUFFIXES.add("deleter");
  }


  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    return new Visitor(holder);
  }

  public class Visitor extends PyInspectionVisitor {

    public Visitor(final ProblemsHolder holder) {
      super(holder);
    }


    @Override
    public void visitPyFile(PyFile node) {
      super.visitPyFile(node);
    }

    @Override
    public void visitPyClass(final PyClass node) {
      super.visitPyClass(node);
      // check property() and @property
      node.scanProperties(new Processor<Property>() {
        @Override
        public boolean process(Property property) {
          PyTargetExpression target = property.getDefinitionSite();
          if (target != null) {
            // target = property(); args may be all funny
            PyCallExpression call = (PyCallExpression)target.findAssignedValue();
            assert call != null : "Property has a null call assigned to it";
            final PyArgumentList arglist = call.getArgumentList();
            assert arglist != null : "Property call has null arglist";
            PyArgumentList.AnalysisResult analysis = arglist.analyzeCall();
            // we assume fget, fset, fdel, doc names
            for (Map.Entry<PyExpression, PyNamedParameter> entry: analysis.getPlainMappedParams().entrySet()) {
              final String param_name = entry.getValue().getName();
              PyExpression argument = PyUtil.peelArgument(entry.getKey());
              assert argument != null : "Parameter mapped to null argument";
              Callable callable = null;
              if (argument instanceof PyReferenceExpression) {
                PsiElement resolved = ((PyReferenceExpression)argument).getReference().resolve();
                if (resolved instanceof PyFunction) callable = (PyFunction)resolved;
                else if (resolved instanceof PyLambdaExpression) callable = (PyLambdaExpression)resolved;
                else {
                  reportStrangeArg(resolved, argument);
                  continue;
                }
              }
              else if (argument instanceof PyLambdaExpression) callable = (PyLambdaExpression)argument;
              else if (! "doc".equals(param_name)) {
                reportStrangeArg(argument, argument);
                continue;
              }
              if ("fget".equals(param_name)) checkGetter(callable, argument);
              else if ("fset".equals(param_name)) checkSetter(callable, argument);
              else if ("fdel".equals(param_name)) checkDeleter(callable, argument);
              else if ("doc".equals(param_name)) {
                PyType type = argument.getType(TypeEvalContext.fast());
                if (! (type instanceof PyClassType && myStringClasses.contains(((PyClassType)type).getPyClass()))) {
                  registerProblem(argument, PyBundle.message("INSP.doc.param.should.be.str"));
                }
              }
            }
          }
          else {
            // @property; we only check getter, others are checked by visitPyFunction
            // getter is always present with this form
            final PyFunction function = property.getGetter().valueOrNull();
            checkGetter(function, getFunctionMarkingElement(function));
          }
          return false;  // always want more
        }

      }, false);
    }

    void reportStrangeArg(PsiElement resolved, PsiElement being_checked) {
      if (! PyUtil.instanceOf(resolved, PySubscriptionExpression.class, PyNoneLiteralExpression.class)) {
        registerProblem(being_checked, PyBundle.message("INSP.strange.arg.want.callable"));
      }
    }

    @Override
    public void visitPyFunction(PyFunction node) {
      super.visitPyFunction(node);
      if (myLevel.isAtLeast(LanguageLevel.PYTHON26)) {
        // check @foo.setter and @foo.deleter
        PyClass cls = node.getContainingClass();
        if (cls != null) {
          final PyDecoratorList decos = node.getDecoratorList();
          if (decos != null) {
            String name = node.getName();
            for (PyDecorator deco : decos.getDecorators()) {
              final PyQualifiedName q_name = deco.getQualifiedName();
              if (q_name != null) {
                List<String> name_parts = q_name.getComponents();
                if (name_parts.size() == 2) {
                  final int suffix_index = SUFFIXES.indexOf(name_parts.get(1));
                  if (suffix_index >= 0) {
                    if (Comparing.equal(name, name_parts.get(0))) {
                      // names are ok, what about signatures?
                      PsiElement markable = getFunctionMarkingElement(node);
                      if (suffix_index == 0) checkSetter(node, markable);
                      else checkDeleter(node, markable);
                    }
                    else {
                      registerProblem(deco, PyBundle.message("INSP.func.property.name.mismatch"));
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    @Nullable
    private PsiElement getFunctionMarkingElement(PyFunction node) {
      if (node == null) return null;
      final ASTNode name_node = node.getNameNode();
      PsiElement markable = node;
      if (name_node != null) markable = name_node.getPsi();
      return markable;
    }


    private void checkGetter(Callable callable, PsiElement being_checked) {
      if (callable != null) {
        checkOneParameter(callable, being_checked, true);
        checkReturnValueAllowed(callable, being_checked, true, PyBundle.message("INSP.getter.return.smth"));
      }
    }

    private void checkSetter(Callable callable, PsiElement being_checked) {
      if (callable != null) {
        // signature: at least two params, more optionals ok; first arg 'self'
        final PyParameterList param_list = callable.getParameterList();
        if (myTwoParamList != null && ! param_list.isCompatibleTo(myTwoParamList)) {
          registerProblem(being_checked, PyBundle.message("INSP.setter.signature.advice"));
        }
        checkForSelf(param_list);
        // no explicit return type
        checkReturnValueAllowed(callable, being_checked, false, PyBundle.message("INSP.setter.should.not.return"));
      }
    }

    private void checkDeleter(Callable callable, PsiElement being_checked) {
      if (callable != null) {
        checkOneParameter(callable, being_checked, false);
        checkReturnValueAllowed(callable, being_checked, false, PyBundle.message("INSP.deleter.should.not.return"));
      }
    }

    private void checkOneParameter(Callable callable, PsiElement being_checked, boolean is_getter) {
      final PyParameterList param_list = callable.getParameterList();
      if (myOneParamList != null && ! param_list.isCompatibleTo(myOneParamList)) {
        if (is_getter) registerProblem(being_checked, PyBundle.message("INSP.getter.signature.advice"));
        else registerProblem(being_checked, PyBundle.message("INSP.deleter.signature.advice"));
      }
      checkForSelf(param_list);
    }

    private void checkForSelf(PyParameterList param_list) {
      PyParameter[] parameters = param_list.getParameters();
      if (parameters.length > 0 && ! PyNames.CANONICAL_SELF.equals(parameters[0].getName())) {
        registerProblem(
          parameters[0], PyBundle.message("INSP.accessor.first.param.is.$0", PyNames.CANONICAL_SELF), ProblemHighlightType.INFO, null,
          new RenameParameterQuickFix(PyNames.CANONICAL_SELF));
      }
    }

    private void checkReturnValueAllowed(Callable callable, PsiElement being_checked, boolean allowed, String message) {
      PyType type = callable.getReturnType();
      if (!allowed ^ (type instanceof PyNoneType)) {
        registerProblem(being_checked, message);
      }
    }
  }

}
