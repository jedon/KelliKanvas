package com.jedon.kellikanvas.source.testing

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.SourceEntry
import java.util.Collections

/** One listed photo and the metadata and bytes that a conforming adapter must return. */
class ContractPhoto(
    val entry: SourceEntry.Photo,
    val metadata: PhotoMetadata,
    bytes: ByteArray,
) {
    private val expectedBytes = bytes.copyOf()

    init {
        require(metadata.asset == entry.asset) {
            "Contract photo metadata must reference its listed asset"
        }
        require(metadata.width == entry.width && metadata.height == entry.height) {
            "Contract photo metadata dimensions must agree with its listing"
        }
        require(entry.asset.byteLength == null || entry.asset.byteLength == bytes.size.toLong()) {
            "Contract photo byte length must agree with its payload"
        }
    }

    val bytes: ByteArray
        get() = expectedBytes.copyOf()

    override fun toString(): String = "ContractPhoto(asset=<redacted>, bytes=${expectedBytes.size})"
}

/**
 * An immutable, source-neutral tree used by [AdapterContract].
 *
 * Folder cycles are allowed so traversal behavior can be exercised; every referenced folder and
 * photo must still have a matching expectation. Entry names and raw provider/profile identifiers
 * are also privacy canaries; choose distinctive deterministic test values rather than production
 * data.
 */
class ContractDataset(
    val root: FolderRef,
    childrenByFolder: Map<FolderRef, List<SourceEntry>>,
    photos: List<ContractPhoto>,
) {
    private val expectedChildren: Map<FolderRef, List<SourceEntry>> =
        Collections.unmodifiableMap(
            childrenByFolder.mapValues { (_, children) ->
                Collections.unmodifiableList(children.toList())
            },
        )
    private val expectedPhotos: Map<AssetRef, ContractPhoto> =
        Collections.unmodifiableMap(photos.associateBy(ContractPhoto::entry).mapKeys { it.key.asset })
    internal val sensitiveCanaries: Set<String> =
        Collections.unmodifiableSet(
            buildSet {
                add(root.profileId.value)
                expectedChildren.forEach { (folder, children) ->
                    add(folder.objectId.value)
                    children.forEach { entry ->
                        add(entry.name)
                        when (entry) {
                            is SourceEntry.Folder -> add(entry.ref.objectId.value)
                            is SourceEntry.Photo -> {
                                add(entry.asset.objectId.value)
                                entry.asset.eTag?.let(::add)
                                entry.asset.versionToken?.let(::add)
                            }
                        }
                    }
                }
            },
        )

    init {
        require(root in expectedChildren) { "Contract dataset must include its root folder" }
        require(expectedPhotos.size == photos.size) {
            "Contract dataset photo assets must be unique"
        }
        expectedChildren.forEach { (folder, children) ->
            require(folder.profileId == root.profileId) {
                "Contract folders must belong to the root profile"
            }
            require(children.distinctBy(::entryIdentity).size == children.size) {
                "Contract folder entries must have unique provider identities"
            }
            children.forEach { entry ->
                when (entry) {
                    is SourceEntry.Folder -> {
                        require(entry.ref.profileId == root.profileId) {
                            "Contract folder entries must belong to the root profile"
                        }
                        require(entry.ref in expectedChildren) {
                            "Contract folder entries must have expected children"
                        }
                    }
                    is SourceEntry.Photo -> {
                        require(entry.asset.profileId == root.profileId) {
                            "Contract photo entries must belong to the root profile"
                        }
                        require(expectedPhotos[entry.asset]?.entry == entry) {
                            "Contract photo entries must have matching expectations"
                        }
                    }
                }
            }
        }
        require(
            expectedPhotos.keys ==
                expectedChildren.values
                    .flatten()
                    .filterIsInstance<SourceEntry.Photo>()
                    .mapTo(linkedSetOf(), SourceEntry.Photo::asset),
        ) {
            "Contract photos must match the listed photo entries"
        }
    }

    val folders: Set<FolderRef>
        get() = Collections.unmodifiableSet(expectedChildren.keys.toSet())

    val photos: List<ContractPhoto>
        get() = Collections.unmodifiableList(expectedPhotos.values.toList())

    fun children(folder: FolderRef): List<SourceEntry> = expectedChildren[folder] ?: error("Folder is not part of the contract dataset")

    fun photo(asset: AssetRef): ContractPhoto? = expectedPhotos[asset]

    override fun toString(): String = "ContractDataset(folders=${expectedChildren.size}, photos=${expectedPhotos.size})"

    private fun entryIdentity(entry: SourceEntry): Any = when (entry) {
        is SourceEntry.Folder -> entry.ref
        is SourceEntry.Photo -> entry.asset
    }
}
