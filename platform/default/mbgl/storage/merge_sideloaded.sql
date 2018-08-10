-- copy over region.
INSERT INTO regions
   SELECT DISTINCT NULL, sr.definition, sr.description -- Merge duplicate regions
    FROM side.regions sr 
    LEFT JOIN regions r ON sr.definition = r.definition
      WHERE r.definition IS NULL;

CREATE TEMPORARY TABLE region_mapping AS
    SELECT sr.id AS side_region_id,
      r.id AS main_region_id
    FROM side.regions sr
    JOIN regions r ON sr.definition = r.definition;

--Create temporary table of tiles that need to be merged.
CREATE TEMPORARY TABLE merged_tiles AS
  SELECT st.id as side_tile_id,
    t.id as main_tile_id,
    st.url_template, st.pixel_ratio, st.z, st.x, st.y, st.expires,
    st.modified, st.etag, st.data, st.compressed,st.accessed, st.must_revalidate,
    t.modified AS main_tile_modified,
    main_region_id
  FROM side.tiles st
  -- only consider region tiles, and not ambient tiles.
  JOIN (SELECT DISTINCT srt.tile_id AS tile_id, rm.main_region_id 
      FROM region_mapping rm
      JOIN side.region_tiles srt ON srt.region_id = rm.side_region_id)
    ON tile_id = st.id
  LEFT JOIN tiles t ON st.url_template = t.url_template AND
       st.pixel_ratio = t.pixel_ratio AND
       st.z = t.z AND
       st.x = t.x AND
       st.y = t.y;

-- insert new tiles
REPLACE INTO tiles
    SELECT mt.main_tile_id, -- use the old ID in case we run a REPLACE. If it doesn't exist yet, it'll be NULL which will auto-assign a new ID.
      mt.url_template, mt.pixel_ratio, mt.z, mt.x, mt.y, mt.expires,
      mt.modified, mt.etag, mt.data, mt.compressed, mt.accessed, mt.must_revalidate
    FROM merged_tiles mt
    WHERE mt.main_tile_id IS NULL -- only consider tiles that don't exist yet in the original database
    OR mt.modified > mt.main_tile_modified; -- ...or tiles that are newer in the side loaded DB.

-- Update region_tiles usage
-- Side databases can have multiple identical regions which map to a single new 
--  region, use DISTINCT pairs to ensure only a single reference is used in the main database.
INSERT INTO region_tiles
  SELECT DISTINCT mt.main_region_id, t.id
  FROM merged_tiles mt
  INNER JOIN tiles t ON mt.url_template = t.url_template AND
      mt.pixel_ratio = t.pixel_ratio AND
      mt.z = t.z AND
      mt.x = t.x AND
      mt.y = t.y
  WHERE mt.main_tile_id IS NULL;

-- Add references for existing tile that did not need to be copied.
-- Use IGNORE for situations where an update only affects some resources.
INSERT OR IGNORE INTO region_tiles
  SELECT DISTINCT mt.main_region_id, mt.main_tile_id
  FROM merged_tiles mt
  WHERE mt.main_tile_id IS NOT NULL;

DROP TABLE merged_tiles;

--Create temporary table of tiles that need to be merged.
CREATE TEMPORARY TABLE merged_resources AS
  SELECT sr.id AS side_resource_id,
    r.id AS main_resource_id, -- use the old ID in case we run a REPLACE. If it doesn't exist yet, it'll be NULL which will auto-assign a new ID.
    sr.url, sr.kind, sr.expires, sr.modified, sr.etag, sr.data, sr.compressed, 
    sr.accessed,
    sr.must_revalidate,
    r.modified AS main_resource_modified,
    main_region_id
  FROM side.resources sr
  -- only consider region resources, and not ambient resources.
  JOIN (SELECT DISTINCT srr.resource_id AS resource_id, rm.main_region_id 
      FROM region_mapping rm
      JOIN side.region_resources srr ON srr.region_id = rm.side_region_id)
    ON resource_id = sr.id
  LEFT JOIN resources r ON sr.url = r.url;

-- copy over resources
REPLACE INTO resources
  SELECT  mr.main_resource_id, 
    mr.url, mr.kind, mr.expires, mr.modified, mr.etag,
    mr.data, mr.compressed, mr.accessed, mr.must_revalidate
  FROM merged_resources mr
    WHERE mr.main_resource_id IS NULL -- only consider resources that don't exist yet in the main database
    OR mr.modified > mr.main_resource_modified; -- ...or resources that are newer in the side loaded DB.

-- Update region_resources usage
-- Side databases can have multiple identical regions which map to a single new 
--  region, use DISTINCT pairs to ensure only a single reference is used in the main database
INSERT INTO region_resources
  SELECT DISTINCT mr.main_region_id, r.id
  FROM merged_resources mr
  INNER JOIN resources r ON mr.url = r.url
  WHERE mr.main_resource_id IS NULL;

-- Add references for existing resources that did not need to be copied.
-- Use IGNORE for situations where an update only affects some resources.
INSERT OR IGNORE INTO region_resources
  SELECT DISTINCT mr.main_region_id, mr.main_resource_id
  FROM merged_resources mr
  WHERE mr.main_resource_id IS NOT NULL;

DROP TABLE merged_resources;

DROP TABLE region_mapping;
