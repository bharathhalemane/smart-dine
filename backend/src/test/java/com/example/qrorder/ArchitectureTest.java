package com.example.qrorder;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.example.qrorder", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    public static final ArchRule commonDoesNotDependOnBusinessModules = noClasses()
            .that().resideInAPackage("..qrorder.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..qrorder.identity..", "..qrorder.tenancy..", "..qrorder.tables..", "..qrorder.dining..",
                    "..qrorder.menu..", "..qrorder.ordering..", "..qrorder.billing..", "..qrorder.payments..",
                    "..qrorder.delivery..", "..qrorder.kitchen..", "..qrorder.realtime..", "..qrorder.platform..");

    @ArchTest
    public static final ArchRule tenancyUsesIdentityOnlyThroughItsServices = noClasses()
            .that().resideInAPackage("..qrorder.tenancy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..qrorder.identity.repository..", "..qrorder.identity.model..");

    @ArchTest
    public static final ArchRule identityDoesNotDependOnTenancy = noClasses()
            .that().resideInAPackage("..qrorder.identity..")
            .should().dependOnClassesThat().resideInAPackage("..qrorder.tenancy..");

}