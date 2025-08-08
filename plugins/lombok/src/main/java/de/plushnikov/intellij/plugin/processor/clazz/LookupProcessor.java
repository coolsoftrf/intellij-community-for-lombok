package de.plushnikov.intellij.plugin.processor.clazz;

import static de.plushnikov.intellij.plugin.LombokClassNames.TO_STRING;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import de.plushnikov.intellij.plugin.LombokClassNames;
import de.plushnikov.intellij.plugin.problem.ProblemSink;
import de.plushnikov.intellij.plugin.processor.LombokPsiElementUsage;
import de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder;
import de.plushnikov.intellij.plugin.quickfix.PsiQuickFixFactory;
import de.plushnikov.intellij.plugin.thirdparty.LombokAddNullAnnotations;
import de.plushnikov.intellij.plugin.util.PsiAnnotationUtil;
import de.plushnikov.intellij.plugin.util.PsiClassUtil;
import de.plushnikov.intellij.plugin.util.PsiMethodUtil;

/**
 * Inspect and validate @Lookup lombok-ext annotation on an enum
 * Creates lookup() static method for the annotated enum
 *
 * @author Coolsoft
 */
public final class LookupProcessor extends AbstractClassProcessor {
  public static final String LOOKUP_FIELD = "field";
  //public static final String LOOKUP_ARG_ORDINAL = "constructorArgumentOrdinal";

  public static final String LOOKUP_METHOD_NAME = "lookup";
  private static final List<String> METHOD_LIST = List.of(LOOKUP_METHOD_NAME);

  public LookupProcessor() {
    super(PsiMethod.class, LombokClassNames.LOOKUP);
  }

  @Override
  protected Collection<String> getNamesOfPossibleGeneratedElements(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation) {
    return METHOD_LIST;
  }

  @Override
  protected boolean validate(@NotNull PsiAnnotation psiAnnotation, @NotNull PsiClass psiClass, @NotNull ProblemSink problemSink) {
    validateAnnotationOnRightType(psiClass, problemSink);
    if (problemSink.success()) {
      validateExistingMethods(psiClass, problemSink);
    }

    if (problemSink.deepValidation()) {
      final Collection<String> excludeProperty = PsiAnnotationUtil.getAnnotationValues(psiAnnotation, "exclude", String.class, List.of());
      final Collection<String> ofProperty = PsiAnnotationUtil.getAnnotationValues(psiAnnotation, "of", String.class, List.of());

      if (!excludeProperty.isEmpty() && !ofProperty.isEmpty()) {
        problemSink.addWarningMessage("inspection.message.exclude.are.mutually.exclusive.exclude.parameter.will.be.ignored")
          .withLocalQuickFixes(() -> PsiQuickFixFactory.createChangeAnnotationParameterFix(psiAnnotation, "exclude", null));
      }
      else {
        validateExcludeParam(psiClass, problemSink, psiAnnotation, excludeProperty);
      }
      validateOfParam(psiClass, problemSink, psiAnnotation, ofProperty);
    }
    return problemSink.success();
  }

  private static void validateAnnotationOnRightType(@NotNull PsiClass psiClass, @NotNull ProblemSink builder) {
    if (!psiClass.isEnum()) {
      builder.addErrorMessage("inspection.message.lookup.only.supported.on.enum.type");
      builder.markFailed();
    }
  }

  private static void validateExistingMethods(@NotNull PsiClass psiClass, @NotNull ProblemSink builder) {
    final boolean methodAlreadyExists = hasLookupMethodDefined(psiClass);
    if (methodAlreadyExists) {
      builder.addWarningMessage("inspection.message.not.generated.s.method.with.same.name.already.exists", LOOKUP_METHOD_NAME)
        .withLocalQuickFixes(() -> PsiQuickFixFactory.createDeleteAnnotationFix(psiClass, TO_STRING));
      builder.markFailed();
    }
  }

  private static boolean hasLookupMethodDefined(@NotNull PsiClass psiClass) {
    final Collection<PsiMethod> classMethods = PsiClassUtil.collectClassMethodsIntern(psiClass);
    return PsiMethodUtil.hasMethodByName(classMethods, LOOKUP_METHOD_NAME, 0);
  }

  @Override
  protected void generatePsiElements(@NotNull PsiClass psiClass,
                                     @NotNull PsiAnnotation psiAnnotation,
                                     @NotNull List<? super PsiElement> target, @Nullable String nameHint) {
    target.addAll(createLookupMethod(psiClass, psiAnnotation));
  }

  @NotNull Collection<PsiMethod> createLookupMethod(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation) {
    if (hasLookupMethodDefined(psiClass)) {
      return Collections.emptyList();
    }

    final PsiManager psiManager = psiClass.getManager();

    final String lookupFieldName = PsiAnnotationUtil.getStringAnnotationValue(psiAnnotation, LOOKUP_FIELD, "");
    final Map<String, PsiType> classFieldTypes = PsiClassUtil.collectClassFieldsIntern(psiClass).stream()
      .collect(Collectors.toMap(PsiField::getName, PsiField::getType));

    //final int constructorArgumentOrdinal = PsiAnnotationUtil.getIntAnnotationValue(psiAnnotation, LOOKUP_ARG_ORDINAL, 0);
    //ToDo: generate correct switch-case construction for delombok
    final String blockText = String.format("return \"%s\";", "null");

    final LombokLightMethodBuilder methodBuilder = new LombokLightMethodBuilder(psiManager, LOOKUP_METHOD_NAME)
      .withContainingClass(psiClass)
      .withNavigationElement(psiAnnotation)
      .withModifier(PsiModifier.PUBLIC)
      .withModifier(PsiModifier.STATIC)
      .withMethodReturnType(JavaPsiFacade.getInstance(psiClass.getProject()).getElementFactory().createType(psiClass))
      .withParameter(lookupFieldName, classFieldTypes.get(lookupFieldName))
      .withBodyText(blockText);

    LombokAddNullAnnotations.createRelevantNonNullAnnotation(psiClass, methodBuilder);
    return Collections.singletonList(methodBuilder);
  }

  @Override
  public LombokPsiElementUsage checkFieldUsage(@NotNull PsiField psiField, @NotNull PsiAnnotation psiAnnotation) {
    final PsiClass containingClass = psiField.getContainingClass();
    if (null != containingClass) {
      if(psiField.getName().equals(PsiAnnotationUtil.getStringAnnotationValue(psiAnnotation, LOOKUP_FIELD, ""))){
        return LombokPsiElementUsage.READ;
      }
    }
    return LombokPsiElementUsage.NONE;
  }
}
