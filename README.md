# Tom's Storage Autocrafting (Fabric 26.3)

Unofficial addon for [Tom's Simple Storage](https://modrinth.com/mod/toms-storage) (26.3-2.12.2, Fabric) that adds AE2-style autocrafting.
All text is translatable (`assets/tsautocraft/lang/*.json`); English and German are included.

## Blocks & items

| Block / item | What it does |
|---|---|
| **Exporter** (+ Framed) | Takes filtered items from the network and pushes them into the inventory it faces (16 items / 8 ticks). No filter: does nothing. |
| **Importer** (+ Framed) | Pulls items from the inventory it faces into the network. No filter: everything. |
| **Pattern Provider** | Full block. Holds 9 patterns and supplies the machine or `minecraft:crafter` it faces. |
| **Pattern Provider Connector** (+ Framed) | Same as the Pattern Provider, in cable-connector shape. Convert between both in a crafting grid. |
| **Pattern Terminal** | Encodes patterns (crafting or processing mode). |
| **Autocrafting Terminal** | Lists craftable items, accepts requests and shows running jobs. |
| **Requester** | Keeps up to 6 items at a target amount and crafts more when the stock drops. |
| **Blank / Encoded Pattern** | Put an encoded pattern into a crafting grid to wipe it. |

Exporter, Importer and the Pattern Provider Connector use the same shape as Tom's Inventory Cable Connector: a plate on the target,
a stub towards the network and cable arms to every connected cable.
**Framed** variants are full blocks that can be painted with Tom's Paint Kit and scraped clean with an axe, exactly like Tom's
framed cables. Recipe: 4 sticks around the block; a framed block plus a water bucket turns it back.

Filter slots accept ghost items (shift click or JEI/REI drag) **and** Tom's real filters (item, tag and polymorphic filter).

## Setup

```
[storage chests]──[Inventory Connector]──cable──┬──[Autocrafting Terminal]
                                                ├──[Pattern Terminal]
                                                ├──[Requester]
                                                ├──[Pattern Provider]→[Crafter]→[chest]←[Importer]──cable
                                                └──[Pattern Provider Connector]→[Furnace] (output below)←[Importer]──cable
```

* All addon blocks connect to Tom's network like cables, **except through their front side**.
* Placement: click on the target (chest, crafter, machine); the front then faces it.
* **Results do not come back automatically.** The crafter drops its result in front of itself: put a chest there and pull it
  into the network with an Importer. For furnaces, empty the output side with an Importer.
* **Do not place machines or crafters directly next to storage chests.** Tom's Inventory Connector picks up every touching
  inventory as storage, so the crafter would become "storage". Keep a gap or exclude it with Tom's Inventory Configurator.

### Crafting patterns (via `minecraft:crafter`)

1. Set the Pattern Terminal to **C** (crafting) and put the ingredients into the 3×3 grid (position = crafter slot, top left = slot 0).
2. The result is computed from the recipe. Put a blank pattern in the top right slot and press **Encode**.
3. Put the pattern into a Pattern Provider that faces a crafter.

The provider places the ingredients into the crafter slots **in exactly this order** and then sends a 2-tick redstone pulse
(only to the crafter). The next delivery waits until the crafter is empty. If the crafter gets stuck (wrong recipe, disabled
slot), the provider returns the leftovers after 1 second.

### Processing patterns

Choose **P** (processing). Click with a stack = full amount, right click = +1 / −1, set the outputs (right column) by hand.
The provider only inserts the ingredients if **everything** fits. In blocking mode (default, "Block: On") it waits until no
ingredient is left in the machine.

### Requesting

Autocrafting Terminal: click an item (double click = request right away), enter an amount, press **Request**. Missing raw
materials are listed in the message line. Intermediates are planned recursively and existing stock is used. Surplus (e.g.
4 planks instead of 1) is reused by later steps.

### Requester

Put an item into a slot (shift click sets the target to 64) and enter the amount. When the network stock drops below it,
the requester starts a job for the difference (at most 1024 per job, one job per row at a time).

## Building

Requires **JDK 25**.

```
gradlew build          # -> build/libs/tsautocraft-fabric-26.3-0.1.0.jar
gradlew runClient      # test client with Tom's Storage + Fabric API
gradlew plannerTest    # tests the crafting planner without Minecraft
```

In IntelliJ: open the folder, import the Gradle project, set the project SDK to 25.

## Known limits (AE2-light)

* No item reservation: if someone takes ingredients out during a job, the job waits until they are back.
* No crafting CPUs, no byte limits; at most 8 parallel jobs per terminal.
* A job counts as done once the requested amount has arrived in the network, at the latest after waiting 2 minutes.
* Items only, no fluids.
