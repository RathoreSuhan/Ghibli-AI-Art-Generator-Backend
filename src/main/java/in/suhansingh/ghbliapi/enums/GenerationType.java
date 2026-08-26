package in.suhansingh.ghbliapi.enums;

/**
 * Which endpoint produced a generation.
 *
 * <p>An enum rather than a free string because the two values are the whole taxonomy and a
 * typo in a stored string would silently split history into two buckets. Stored by
 * <em>name</em> in Mongo (Spring Data's default for enums), so renaming a constant rewrites
 * the meaning of every document already written — the names are part of the API contract
 * that Phase 5 filters on.
 *
 * <p>The variant-parameter argument for MongoDB (PLAN.md §3) lands here: an
 * {@link #IMAGE_TO_IMAGE} record has an init image behind it and no user-chosen style, while
 * a {@link #TEXT_TO_IMAGE} record has a style the user picked and no source image. Same
 * collection, genuinely different attribute sets.
 */
public enum GenerationType {

    /** {@code POST /api/v1/generate-from-text} — prompt plus a user-selected style. */
    TEXT_TO_IMAGE,

    /** {@code POST /api/v1/generate} — an uploaded photo restyled with a fixed preset. */
    IMAGE_TO_IMAGE
}
