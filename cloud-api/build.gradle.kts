/**
 * The public contract. Deliberately dependency-free: this module is what
 * third-party plugins and modules compile against, and every dependency
 * added here becomes a dependency they are forced to inherit or shade.
 */
plugins {
    java
    `java-library`
}
