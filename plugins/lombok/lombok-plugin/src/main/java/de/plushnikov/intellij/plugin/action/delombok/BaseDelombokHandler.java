package de.plushnikov.intellij.plugin.action.delombok;

import com.intellij.openapi.command.undo.UndoUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameterList;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import de.plushnikov.intellij.plugin.processor.AbstractProcessor;
import de.plushnikov.intellij.plugin.settings.ProjectSettings;
import de.plushnikov.intellij.plugin.util.PsiMethodUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class BaseDelombokHandler {
  private final boolean processInnerClasses;
  private final Collection<AbstractProcessor> lombokProcessors;

  protected BaseDelombokHandler(AbstractProcessor... lombokProcessors) {
    this(false, lombokProcessors);
  }

  protected BaseDelombokHandler(boolean processInnerClasses, AbstractProcessor... lombokProcessors) {
    this.processInnerClasses = processInnerClasses;
    this.lombokProcessors = new ArrayList<AbstractProcessor>(Arrays.asList(lombokProcessors));
  }

  public void invoke(@NotNull Project project, @NotNull PsiFile psiFile, @NotNull PsiClass psiClass) {
    if (psiFile.isWritable()) {
      invoke(project, psiClass, processInnerClasses);
      finish(project, psiFile);
    }
  }

  public void invoke(@NotNull Project project, @NotNull PsiJavaFile psiFile) {
    for (PsiClass psiClass : psiFile.getClasses()) {
      invoke(project, psiClass, true);
    }
    finish(project, psiFile);
  }

  private void invoke(Project project, PsiClass psiClass, boolean processInnerClasses) {
    Collection<PsiAnnotation> processedAnnotations = new HashSet<PsiAnnotation>();
    for (AbstractProcessor lombokProcessor : lombokProcessors) {
      processedAnnotations.addAll(processClass(project, psiClass, lombokProcessor));
    }

    if (processInnerClasses) {
      for (PsiClass innerClass : psiClass.getAllInnerClasses()) {
        invoke(project, innerClass, processInnerClasses);
      }
    }
    deleteAnnotations(processedAnnotations);
  }

  private void finish(Project project, PsiFile psiFile) {
    JavaCodeStyleManager.getInstance(project).optimizeImports(psiFile);
    UndoUtil.markPsiFileForUndo(psiFile);
  }

  protected Collection<PsiAnnotation> processClass(@NotNull Project project, @NotNull PsiClass psiClass, @NotNull AbstractProcessor lombokProcessor) {
    Collection<PsiAnnotation> psiAnnotations = lombokProcessor.collectProcessedAnnotations(psiClass);

    List<? super PsiElement> psiElements = lombokProcessor.process(psiClass);

    ProjectSettings.setEnabledInProject(project, false);
    try {
      for (Object psiElement : psiElements) {
        final PsiElement element = rebuildPsiElement(project, (PsiElement) psiElement);
        if (null != element) {
          psiClass.add(element);
        }
      }
    } finally {
      ProjectSettings.setEnabledInProject(project, true);
    }

    return psiAnnotations;
  }

  public Collection<PsiAnnotation> collectProcessableAnnotations(@NotNull PsiClass psiClass) {
    Collection<PsiAnnotation> result = new ArrayList<PsiAnnotation>();

    for (AbstractProcessor lombokProcessor : lombokProcessors) {
      result.addAll(lombokProcessor.collectProcessedAnnotations(psiClass));
    }

    return result;
  }

  private PsiElement rebuildPsiElement(@NotNull Project project, PsiElement psiElement) {
    if (psiElement instanceof PsiMethod) {
      return rebuildMethod(project, (PsiMethod) psiElement);
    } else if (psiElement instanceof PsiField) {
      return rebuildField(project, (PsiField) psiElement);
    } else if (psiElement instanceof PsiClass) {
      return rebuildClass(project, (PsiClass) psiElement);
    }
    return null;
  }

  private PsiClass rebuildClass(@NotNull Project project, @NotNull PsiClass fromClass) {
    final PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

    PsiClass resultClass = elementFactory.createClass(fromClass.getName());
    copyModifiers(fromClass.getModifierList(), resultClass.getModifierList());

    for (PsiField psiField : fromClass.getFields()) {
      resultClass.add(rebuildField(project, psiField));
    }
    for (PsiMethod psiMethod : fromClass.getMethods()) {
      resultClass.add(rebuildMethod(project, psiMethod));
    }

    return (PsiClass) CodeStyleManager.getInstance(project).reformat(resultClass);
  }

  private PsiMethod rebuildMethod(@NotNull Project project, @NotNull PsiMethod fromMethod) {
    final PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

    final PsiMethod resultMethod;
    final PsiType returnType = fromMethod.getReturnType();
    if (null == returnType) {
      resultMethod = elementFactory.createConstructor(fromMethod.getName());
    } else {
      resultMethod = elementFactory.createMethod(fromMethod.getName(), returnType);
    }

    final PsiTypeParameterList fromMethodTypeParameterList = fromMethod.getTypeParameterList();
    if (null != fromMethodTypeParameterList) {
      PsiTypeParameterList typeParameterList = PsiMethodUtil.createTypeParameterList(fromMethodTypeParameterList);
      if (null != typeParameterList) {
        resultMethod.addAfter(typeParameterList, resultMethod.getModifierList());
      }
    }

    final PsiClassType[] referencedTypes = fromMethod.getThrowsList().getReferencedTypes();
    if (referencedTypes.length > 0) {
      PsiJavaCodeReferenceElement[] refs = new PsiJavaCodeReferenceElement[referencedTypes.length];
      for (int i = 0; i < refs.length; i++) {
        refs[i] = elementFactory.createReferenceElementByType(referencedTypes[i]);
      }
      resultMethod.getThrowsList().replace(elementFactory.createReferenceList(refs));
    }

    for (PsiParameter parameter : fromMethod.getParameterList().getParameters()) {
      PsiParameter param = elementFactory.createParameter(parameter.getName(), parameter.getType());
      resultMethod.getParameterList().add(param);
    }

    final PsiModifierList fromMethodModifierList = fromMethod.getModifierList();
    final PsiModifierList resultMethodModifierList = resultMethod.getModifierList();
    copyModifiers(fromMethodModifierList, resultMethodModifierList);
    for (PsiAnnotation psiAnnotation : fromMethodModifierList.getAnnotations()) {
      final PsiAnnotation annotation = resultMethodModifierList.addAnnotation(psiAnnotation.getQualifiedName());
      for (PsiNameValuePair nameValuePair : psiAnnotation.getParameterList().getAttributes()) {
        annotation.setDeclaredAttributeValue(nameValuePair.getName(), nameValuePair.getValue());
      }
    }

    PsiCodeBlock body = fromMethod.getBody();
    if (null != body) {
      resultMethod.getBody().replace(body);
    }

    return (PsiMethod) CodeStyleManager.getInstance(project).reformat(resultMethod);
  }

  private void copyModifiers(PsiModifierList fromModifierList, PsiModifierList resultModifierList) {
    for (String modifier : PsiModifier.MODIFIERS) {
      resultModifierList.setModifierProperty(modifier, fromModifierList.hasModifierProperty(modifier));
    }
  }

  private PsiField rebuildField(@NotNull Project project, @NotNull PsiField fromField) {
    final PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

    final PsiField resultField = elementFactory.createField(fromField.getName(), fromField.getType());
    copyModifiers(fromField.getModifierList(), resultField.getModifierList());
    resultField.setInitializer(fromField.getInitializer());

    return (PsiField) CodeStyleManager.getInstance(project).reformat(resultField);
  }

  private void deleteAnnotations(Collection<PsiAnnotation> psiAnnotations) {
    for (PsiAnnotation psiAnnotation : psiAnnotations) {
      psiAnnotation.delete();
    }
  }
}
