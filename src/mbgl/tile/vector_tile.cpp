#include <mbgl/tile/vector_tile.hpp>
#include <mbgl/tile/vector_tile_data.hpp>
#include <mbgl/tile/tile_loader_impl.hpp>
#include <mbgl/renderer/tile_parameters.hpp>

namespace mbgl {

VectorTile::VectorTile(const OverscaledTileID& id_,
                       std::string sourceID_,
                       const TileParameters& parameters,
                       const Tileset& tileset)
    : GeometryTile(id_, sourceID_, parameters), loader(*this, id_, parameters, tileset) {
}

void VectorTile::setNecessity(Necessity necessity) {
    loader.setNecessity(necessity);
}

void VectorTile::setError(std::exception_ptr err, const bool complete) {
    (void)complete;
    GeometryTile::setError(err);
}

void VectorTile::setData(optional<std::shared_ptr<const std::string>> data,
                         optional<Timestamp> modified_,
                         optional<Timestamp> expires_,
                         const bool complete) {
    (void)complete;
    modified = modified_;
    expires = expires_;

    if (data) {
        GeometryTile::setData(*data ? std::make_unique<VectorTileData>(*data) : nullptr);
    }
}

} // namespace mbgl
