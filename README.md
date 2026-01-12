# qupath-extension-mld

A QuPath extension to work with **Visiopharm MLD files and TSV exports**, enabling fast import of annotations, labels, and project structure from Visiopharm into QuPath.

---

## What this extension does

Visiopharm projects typically generate:

* **`.mld` files**
  Binary containers holding:

  * geometry (objects, regions, ROIs)
  * the associated image path

* **`.tsv` measurement files**
  Flat tables with:

  * per-object measurements
  * links back to an MLD file via the `LayerData` column.

This extension allows you to:

* Read `.mld` files directly in QuPath
* Extract:

  * image paths
  * ROIs
  * labels
* Convert them into native **QuPath annotations and detections**
* Use Visiopharm TSV exports to:

  * discover which images belong to a project
  * group results by layer / slide / sample
  * drive automated QuPath project creation

---


## Example: Load annotations from an MLD into QuPath

```groovy
import qupath.ext.mld.MldTools

// Read the MLD file
def mldFile = new File("C:/sample_data/ses1_4_Result.mld")
def mldData = MldTools.readMldBinary(mldFile)

// Extract image path from the embedded XML
def imagePath = MldTools.getImagePathFromMld(mldData)
def targetFileName = imagePath.tokenize('/\\').last()

// Find the matching QuPath image entry
def matchEntry = null
project.getImageList().each { entry ->
    def imageURI = entry.getURIs().iterator().next()
    def entryFileName = imageURI.getPath().tokenize('/\\').last()
    if (entryFileName == targetFileName) {
        matchEntry = entry
        return true
    }
}

if (matchEntry == null) {
    println "No matching image found"
    return
}

def imageData = matchEntry.readImageData()

// Clear existing objects
imageData.getHierarchy().clearAll()

// Convert and add MLD objects
def pathObjects = MldTools.convertMldToPathObjects(mldData, imageData.getServer())
imageData.getHierarchy().addObjects(pathObjects)
imageData.getHierarchy().resolveHierarchy()

matchEntry.saveImageData(imageData)
```

---

## Fast TSV column extraction from Visiopharm exports

Visiopharm TSV files are often **multi-GB** and can contain **millions of rows**.
Using `String.split()` or CSV parsers is slow and memory-hungry.

`MldTools` includes a **streaming, zero-regex TSV reader** to extract **unique values from a column** at near disk speed.

This is ideal for columns like:

* `LayerData`
* `Image`

### Example

```groovy
import qupath.ext.mld.MldTools
import java.nio.file.Paths

def tsvPath = "your_file.tsv"
def targetColumn = "LayerData"

def path = Paths.get(tsvPath)
def layers = MldTools.collectUniqueTsvValues(path, targetColumn)

layers.each { println it }
```

Output might look like:

```
C:\ProgramData\Visiopharm\Database\d67\e67288\ses3187_670862_Result.mld
C:\ProgramData\Visiopharm\Database\d67\e67275\ses3187_670806_Result.mld
C:\ProgramData\Visiopharm\Database\d67\e67271\ses3187_670766_Result.mld
...
```

This allows you to:

* discover which MLD files belong to a TSV
* reconstruct QuPath projects automatically by linking images, annotations and labels

---

## Typical pipeline

A realistic Visiopharm → QuPath workflow becomes:

1. Export measurements from Visiopharm (`.tsv`)
2. Extract unique `LayerData` (MLD paths)
3. For each MLD:

   * read image path
   * create or find QuPath image entry
   * import annotations
4. Build a full QuPath project automatically

---

## Build the extension

Building the extension with Gradle should be pretty easy - you don't even need to install Gradle separately, because the 
[Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) will take care of that.

Open a command prompt, navigate to where the code lives, and use
```bash
gradlew build
```

The built extension should be found inside `build/libs`.
You can drag this onto QuPath to install it.
You'll be prompted to create a user directory if you don't already have one.


Note that *QuPath itself* is available under the GPL, so you do have to abide by those terms: see https://github.com/qupath/qupath for more.
