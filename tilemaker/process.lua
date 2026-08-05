-- dok2 offline basemap — Tilemaker process script (Tilemaker 3.x, v2 API).
-- Targets the API of the installed 3.1.0: global functions Find(), Layer(),
-- Attribute(), ... (NOT the pre-3.0 way:Find() style, and there is no
-- osm2pgsql-style object API in this version).
--
-- LAYER CONTRACT (exactly seven source layers; names are load-bearing — the
-- app's MapScreen renders these exact names, do not rename):
--   landuse  (polygons)          natural/landuse/leisure land cover
--   water    (polygons + lines)  lakes, reservoirs, rivers, streams, canals
--   contour  (lines)             REQUIRES SRTM elevation input at build time;
--                                when no elevation data is supplied the layer
--                                is absent
--   path     (lines)             footways, paths, steps, bridleways, cycleways
--   track    (lines)             unpaved highway=track roads
--   peak     (points)            natural=peak / natural=volcano, with ele
--   hut      (points)            alpine huts, wilderness huts, shelters
--
-- CONTOUR (SRTM dependency): Tilemaker has no built-in DEM reader. Contours
-- are generated OUTSIDE tilemaker — gdal_contour over SRTM .hgt cells — into
-- a shapefile, then merged at build time by adding a shapefile source to the
-- "contour" layer in config.json, e.g.:
--   "contour": { "minzoom": 0, "maxzoom": 15,
--                "source": "contours/contours.shp",
--                "source_columns": ["elevation"] }
-- This script never routes OSM ways/nodes to "contour", so a build without
-- elevation data simply produces no contour features — the layer is absent.
--
-- Buildings and road-classification detail are deliberately dropped (see
-- DOCUMENTATION.md "Map and elevation data").

-- Nodes are only processed if one of these keys is present
node_keys = { "natural", "tourism", "amenity" }

function node_function(node)
	local natural = Find("natural")
	local tourism = Find("tourism")
	local amenity = Find("amenity")

	-- Peaks
	if natural == "peak" or natural == "volcano" then
		Layer("peak", false)
		Attribute("class", natural)
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
		local ele = tonumber(Find("ele"))
		if ele ~= nil then AttributeNumeric("ele", ele) end
	end

	-- Huts and shelters
	if tourism == "alpine_hut" or tourism == "wilderness_hut" or amenity == "shelter" then
		Layer("hut", false)
		if tourism ~= "" then
			Attribute("class", tourism)
		else
			Attribute("class", amenity)
		end
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end
end

function way_function()
	local highway = Find("highway")
	local waterway = Find("waterway")
	local natural = Find("natural")
	local landuse = Find("landuse")
	local leisure = Find("leisure")

	-- Paths
	if highway == "path" or highway == "footway" or highway == "steps" or
	   highway == "cycleway" or highway == "bridleway" or highway == "pedestrian" or
	   highway == "living_street" then
		Layer("path", false)
		Attribute("class", highway)
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end

	-- Tracks (dirt / forest / farm roads)
	if highway == "track" then
		Layer("track", false)
		Attribute("class", "track")
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end

	-- Water: lines (rivers, streams, canals, ditches)
	if waterway == "river" or waterway == "stream" or waterway == "canal" or
	   waterway == "ditch" or waterway == "drain" then
		Layer("water", false)
		Attribute("class", waterway)
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end

	-- Water: polygons (lakes, ponds, reservoirs, basins, riverbanks)
	if natural == "water" or landuse == "reservoir" or landuse == "basin" or
	   waterway == "riverbank" then
		Layer("water", true)
		if natural == "water" then
			local water = Find("water")
			if water ~= "" then
				Attribute("class", water)
			else
				Attribute("class", "water")
			end
		elseif waterway == "riverbank" then
			Attribute("class", "riverbank")
		else
			Attribute("class", landuse)
		end
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end

	-- Land cover (natural vegetation and surfaces)
	if natural == "wood" or natural == "scrub" or natural == "heath" or
	   natural == "grassland" or natural == "wetland" or natural == "glacier" or
	   natural == "bare_rock" or natural == "scree" or natural == "sand" or
	   natural == "fell" or natural == "shingle" then
		Layer("landuse", true)
		Attribute("class", natural)
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end

	-- Land cover (managed land)
	if landuse == "forest" or landuse == "meadow" or landuse == "farmland" or
	   landuse == "orchard" or landuse == "vineyard" or landuse == "grass" or
	   landuse == "quarry" or landuse == "allotments" or landuse == "cemetery" then
		Layer("landuse", true)
		Attribute("class", landuse)
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end

	-- Land cover (leisure green spaces)
	if leisure == "park" or leisure == "garden" or leisure == "golf_course" or
	   leisure == "nature_reserve" or leisure == "pitch" then
		Layer("landuse", true)
		Attribute("class", leisure)
		local name = Find("name")
		if name ~= "" then Attribute("name", name) end
	end
end
