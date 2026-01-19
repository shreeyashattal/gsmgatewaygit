cmake_minimum_required(VERSION 3.22.1)

project(gsm_sip_gateway C)

set(CMAKE_C_STANDARD 11)

add_subdirectory(pjsip)

add_library(native-lib SHARED
    native-lib.c
    sip_core.c
    sip_registrar.c
    sip_auth.c
    sip_location.c
    sip_invite.c
)

target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(native-lib
    pjsip
    ${log-lib}
)

