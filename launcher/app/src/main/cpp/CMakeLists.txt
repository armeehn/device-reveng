# libriposte_ais.so — the dlopen shim over the unit's libais_camera.so (see ais_camera.cpp).
# Nothing vendor-side is linked here: the client library exists only on the head unit.
cmake_minimum_required(VERSION 3.22.1)
project(riposte_ais LANGUAGES CXX)

add_library(riposte_ais SHARED ais_camera.cpp)
target_compile_features(riposte_ais PRIVATE cxx_std_17)
target_link_libraries(riposte_ais PRIVATE log)
