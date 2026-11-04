# edhoc_peer: libedhoc as a line-protocol EDHOC peer for kcoap's interop tests (see edhoc_peer.c).
#
# Built inside libedhoc's own build tree, so it uses exactly the pinned library, its vendored crypto and its
# reference cipher suites: the Dockerfile appends
#
#   add_subdirectory(/src/kcoap-peer ${CMAKE_BINARY_DIR}/kcoap-peer)
#
# to libedhoc's top-level CMakeLists.txt. The reference cipher suites are an INTERFACE library whose sources
# every consumer compiles, as libedhoc's own tests do (tests/cmake/edhoc_tests.cmake).

include(${CMAKE_SOURCE_DIR}/cmake/sources.cmake)

add_executable(edhoc_peer edhoc_peer.c ${LIBEDHOC_CIPHER_SUITE_SOURCES})

set_source_files_properties(${LIBEDHOC_CIPHER_SUITE_SOURCES} PROPERTIES
    COMPILE_FLAGS "-Wno-format -Wno-sign-conversion -Wno-conversion")

target_include_directories(edhoc_peer PRIVATE ${LIBEDHOC_INTERNAL_INCLUDE_DIR})

target_link_libraries(edhoc_peer PRIVATE
    libedhoc::edhoc
    libedhoc::cipher_suites
    libedhoc::backend_memory)

if(CONFIG_LIBEDHOC_CIPHER_SUITE_PQC_1_ENABLE)
    target_include_directories(edhoc_peer PRIVATE
        ${CMAKE_BINARY_DIR}/externals/liboqs/include
        ${LIBEDHOC_ROOT_DIR}/externals/liboqs/src
        ${XKCP_INCLUDE_DIR})
    add_dependencies(edhoc_peer xkcp_build)
endif()

install(TARGETS edhoc_peer RUNTIME DESTINATION bin)
