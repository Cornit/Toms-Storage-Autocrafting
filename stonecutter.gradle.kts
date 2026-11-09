plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3-fabric"

// Constants for Stonecutter comments in the sources: //? if fabric {...} / //? if neoforge {...} / //? if >=26.4 {...}
stonecutter parameters {
    val (_, loader) = current.project.split('-', limit = 2)

    // Version and loader specific entries of stonecutter.properties.toml
    properties {
        tags(current.version, loader)
    }

    constants {
        match(loader, "fabric", "neoforge")
    }
}
