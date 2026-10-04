
# MeshCut controls

- Hold right-click with a lightsaber to ignite and steer it; release to commit the block cut.
- Ctrl + mouse wheel adjusts the held saber from 25% to 200% length, even when off. Each saber remembers its length across ignition and saves.
- G performs a random swing; hold V while moving the mouse to reposition the saber.
- With a lightsaber in your main hand (lit or unlit), aim at a cut block and hold H to inspect the selected fragment: blue marks its material, orange marks remaining contacts with neighbouring blocks. The hint and view only appear while holding the saber. This is a local contact view, not a prediction of whether the whole structure will fall. Keys can be rebound.
- With an empty main hand, hold right-click on a cut-off piece to grab it; the server confirms or explains why it cannot be grabbed. Scroll to adjust distance.

Run `gradlew check` for geometry, projection, stroke-simplification and numerical-boundary checks; these do not start GameTest.

Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
