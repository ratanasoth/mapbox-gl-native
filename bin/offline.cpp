#include <mbgl/util/default_styles.hpp>
#include <mbgl/util/run_loop.hpp>
#include <mbgl/util/string.hpp>

#include <mbgl/storage/default_file_source.hpp>

#include <args/args.hxx>

#include <cstdlib>
#include <iostream>
#include <csignal>
#include <atomic>
#include <sstream>

using namespace std::literals::chrono_literals;

int main(int argc, char *argv[]) {
    args::ArgumentParser argumentParser("Mapbox GL offline tool");
    args::HelpFlag helpFlag(argumentParser, "help", "Display this help menu", {'h', "help"});

    args::ValueFlag<std::string> tokenValue(argumentParser, "key", "Mapbox access token", {'t', "token"});
    args::ValueFlag<std::string> styleValue(argumentParser, "URL", "Map stylesheet", {'s', "style"});
    args::ValueFlag<std::string> outputValue(argumentParser, "file", "Output database file name", {'o', "output"});
    args::ValueFlag<std::string> apiBaseValue(argumentParser, "URL", "API Base URL", {'a', "apiBaseURL"});
    args::ValueFlag<std::string> mergePathValue(argumentParser, "merge", "Databse to merge from", {'m', "merge"});
    args::ValueFlag<std::string> inputValue(argumentParser, "input", "Databse to merge into. Use with --merge option.", {'i', "input"});
    
    args::ValueFlag<double> northValue(argumentParser, "degrees", "North latitude", {"north"});
    args::ValueFlag<double> westValue(argumentParser, "degrees", "West longitude", {"west"});
    args::ValueFlag<double> southValue(argumentParser, "degrees", "South latitude", {"south"});
    args::ValueFlag<double> eastValue(argumentParser, "degrees", "East longitude", {"east"});
    args::ValueFlag<double> minZoomValue(argumentParser, "number", "Min zoom level", {"minZoom"});
    args::ValueFlag<double> maxZoomValue(argumentParser, "number", "Max zoom level", {"maxZoom"});
    args::ValueFlag<double> pixelRatioValue(argumentParser, "number", "Pixel ratio", {"pixelRatio"});

    try {
        argumentParser.ParseCLI(argc, argv);
    } catch (const args::Help&) {
        std::cout << argumentParser;
        exit(0);
    } catch (const args::ParseError& e) {
        std::cerr << e.what() << std::endl;
        std::cerr << argumentParser;
        exit(1);
    } catch (const args::ValidationError& e) {
        std::cerr << e.what() << std::endl;
        std::cerr << argumentParser;
        exit(2);
    }

    std::string style = styleValue ? args::get(styleValue) : mbgl::util::default_styles::streets.url;

    mbgl::optional<std::string> mergePath = {};
    if (mergePathValue) mergePath = args::get(mergePathValue);
    mbgl::optional<std::string> inputDb = {};
    if (inputValue) inputDb = args::get(inputValue);

    // Bay area
    const double north = northValue ? args::get(northValue) : 37.2;
    const double west = westValue ? args::get(westValue) : -122.8;
    const double south = southValue ? args::get(southValue) : 38.1;
    const double east = eastValue ? args::get(eastValue) : -121.7;

    const double minZoom = minZoomValue ? args::get(minZoomValue) : 0.0;
    const double maxZoom = maxZoomValue ? args::get(maxZoomValue) : 15.0;
    const double pixelRatio = pixelRatioValue ? args::get(pixelRatioValue) : 1.0;
    const std::string output = outputValue ? args::get(outputValue) : "offline.db";

    const char* tokenEnv = getenv("MAPBOX_ACCESS_TOKEN");
    const std::string token = tokenValue ? args::get(tokenValue) : (tokenEnv ? tokenEnv : std::string());
    
    const std::string apiBaseURL = apiBaseValue ? args::get(apiBaseValue) : mbgl::util::API_BASE_URL;

    using namespace mbgl;

    util::RunLoop loop;
    DefaultFileSource fileSource(output, ".");
    std::unique_ptr<OfflineRegion> region;

    fileSource.setAccessToken(token);
    fileSource.setAPIBaseURL(apiBaseURL);

    if (inputDb && mergePath) {
        DefaultFileSource inputSource(*inputDb, ".");
        inputSource.setAccessToken(token);
        inputSource.setAPIBaseURL(apiBaseURL);
        
        int retCode = 0;
        std::cout << "Start Merge" << std::endl;
        inputSource.mergeOfflineRegions(*mergePath,  [&] (std::exception_ptr error, optional<std::vector<OfflineRegion>> result) {

            if (error) {
                std::cerr << "Error merging database: " << util::toString(error) << std::endl;
                retCode = 1;
            } else {
                std::cout << " Added " << result->size() << " Regions" << std::endl;
                std::cout << "Finished Merge" << std::endl;
            }
            loop.stop();
        });
        loop.run();
        return retCode;
    }

    LatLngBounds boundingBox = LatLngBounds::hull(LatLng(north, west), LatLng(south, east));
    OfflineTilePyramidRegionDefinition definition(style, boundingBox, minZoom, maxZoom, pixelRatio);
    OfflineRegionMetadata metadata;

    class Observer : public OfflineRegionObserver {
    public:
        Observer(OfflineRegion& region_, DefaultFileSource& fileSource_, util::RunLoop& loop_, mbgl::optional<std::string> mergePath_)
            : region(region_),
              fileSource(fileSource_),
              loop(loop_),
              mergePath(mergePath_),
              start(util::now()) {
        }

        void statusChanged(OfflineRegionStatus status) override {
            if (status.downloadState == OfflineRegionDownloadState::Inactive) {
                std::cout << "stopped" << std::endl;
                loop.stop();
                return;
            }

            std::string bytesPerSecond = "-";

            auto elapsedSeconds = (util::now() - start) / 1s;
            if (elapsedSeconds != 0) {
                bytesPerSecond = util::toString(status.completedResourceSize / elapsedSeconds);
            }

            std::cout << status.completedResourceCount << " / " << status.requiredResourceCount
                      << " resources"
                      << (status.requiredResourceCountIsPrecise ? "; " : " (indeterminate); ")
                      << status.completedResourceSize << " bytes downloaded"
                      << " (" << bytesPerSecond << " bytes/sec)"
                      << std::endl;

            if (status.complete()) {
                std::cout << "Finished Download" << std::endl;
                if (mergePath) {
                    std::cout << "Start Merge" << std::endl;
                    fileSource.mergeOfflineRegions(*mergePath,  [&] (std::exception_ptr, optional<std::vector<OfflineRegion>> ) {
                        std::cout << "Finished Merge" << std::endl;
                        loop.stop();
                    });
                
                } else {
                    loop.stop();
                }
            }
        }

        void responseError(Response::Error error) override {
            std::cerr << error.reason << " downloading resource: " << error.message << std::endl;
        }

        void mapboxTileCountLimitExceeded(uint64_t limit) override {
            std::cerr << "Error: reached limit of " << limit << " offline tiles" << std::endl;
        }

        OfflineRegion& region;
        DefaultFileSource& fileSource;
        util::RunLoop& loop;
        mbgl::optional<std::string> mergePath;
        Timestamp start;
    };

    static auto stop = [&] {
        if (region) {
            std::cout << "Stopping download... ";
            fileSource.setOfflineRegionDownloadState(*region, OfflineRegionDownloadState::Inactive);
        }
    };

    std::signal(SIGINT, [] (int) { stop(); });

    fileSource.createOfflineRegion(definition, metadata, [&] (std::exception_ptr error, optional<OfflineRegion> region_) {
        if (error) {
            std::cerr << "Error creating region: " << util::toString(error) << std::endl;
            loop.stop();
            exit(1);
        } else {
            assert(region_);
            region = std::make_unique<OfflineRegion>(std::move(*region_));
            fileSource.setOfflineRegionObserver(*region, std::make_unique<Observer>(*region, fileSource, loop, mergePath));
            fileSource.setOfflineRegionDownloadState(*region, OfflineRegionDownloadState::Active);
        }
    });

    loop.run();
    return 0;
}
