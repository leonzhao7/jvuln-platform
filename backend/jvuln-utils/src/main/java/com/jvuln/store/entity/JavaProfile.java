package com.jvuln.store.entity;

import javax.persistence.*;

@Entity
@Table(name = "java_profile")
public class JavaProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "java_profile_seq")
    @SequenceGenerator(name = "java_profile_seq", sequenceName = "java_profile_seq", allocationSize = 1, initialValue = 100)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "java_version", nullable = false, length = 10)
    private String javaVersion;

    @Column(name = "java_home", nullable = false, length = 500)
    private String javaHome;

    @Column(name = "spring_boot_version", nullable = false, length = 20)
    private String springBootVersion;

    @Column(name = "maven_java_version", nullable = false, length = 10)
    private String mavenJavaVersion;

    @Column(name = "syntax_constraints", length = 500)
    private String syntaxConstraints;

    @Column(name = "is_default")
    private Boolean isDefault = Boolean.FALSE;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
    public String getJavaHome() { return javaHome; }
    public void setJavaHome(String javaHome) { this.javaHome = javaHome; }
    public String getSpringBootVersion() { return springBootVersion; }
    public void setSpringBootVersion(String springBootVersion) { this.springBootVersion = springBootVersion; }
    public String getMavenJavaVersion() { return mavenJavaVersion; }
    public void setMavenJavaVersion(String mavenJavaVersion) { this.mavenJavaVersion = mavenJavaVersion; }
    public String getSyntaxConstraints() { return syntaxConstraints; }
    public void setSyntaxConstraints(String syntaxConstraints) { this.syntaxConstraints = syntaxConstraints; }
    public Boolean getIsDefault() { return Boolean.TRUE.equals(isDefault); }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
}
