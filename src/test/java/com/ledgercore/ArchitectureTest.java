package com.ledgercore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.ledgercore.identity.User;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.ledgercore");

  @Test
  void businessModulesHaveNoCircularDependencies() {
    slices().matching("com.ledgercore.(*)..").should().beFreeOfCycles().check(CLASSES);
  }

  @Test
  void accountingDomainDoesNotDependOnFrameworks() {
    noClasses()
        .that()
        .resideInAPackage("..ledger.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
        .check(CLASSES);
  }

  @Test
  void financialPostingDoesNotDependOnRedisOrKafkaAvailability() {
    noClasses()
        .that()
        .resideInAnyPackage("..ledger..", "..transfers..", "..accounts..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.data.redis..", "org.springframework.kafka..")
        .check(CLASSES);
  }

  @Test
  void controllersNeverReturnIdentityEntities() {
    noMethods()
        .that()
        .areDeclaredInClassesThat()
        .haveSimpleNameEndingWith("Controller")
        .should()
        .haveRawReturnType(User.class)
        .check(CLASSES);
  }

  @Test
  void unusedVulnerableViewFeaturesCannotBeIntroduced() {
    for (String forbidden :
        new String[] {
          "org.springframework.web.servlet.view.xslt.XsltView",
          "org.springframework.web.servlet.mvc.method.annotation.SseEmitter",
          "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter",
          "org.springframework.web.servlet.view.FragmentsRendering"
        }) {
      noClasses().should().dependOnClassesThat().haveFullyQualifiedName(forbidden).check(CLASSES);
    }
  }
}
