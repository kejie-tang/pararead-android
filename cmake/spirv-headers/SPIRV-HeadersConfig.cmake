# Header-only dependency, fetched and verified by scripts/fetch_vulkan.py.
get_filename_component(PARAREAD_SOURCE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED GLOBAL)
set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${PARAREAD_SOURCE_ROOT}/.references/SPIRV-Headers/include")
set(SPIRV-Headers_FOUND TRUE)
