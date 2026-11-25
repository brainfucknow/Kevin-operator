#include "sign.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>

int main(int argc, char** argv) {
  const char* which = argc > 1 ? argv[1] : "";
  const bool expect_failure = argc > 2 && std::strcmp(argv[2], "explicit") == 0;
  if (argc > 2) {
    const char* context = std::getenv("KEVIN_CPP_TEST_CONTEXT");
    const char* marker = expect_failure ? "explicit.marker" : "default.marker";
    std::FILE* file = std::fopen(marker, "r");
    const bool valid_context = file && context && std::strcmp(context, argv[2]) == 0;
    if (file) {
      std::fclose(file);
    }
    if (!valid_context) {
      std::puts("missing CTest execution context");
      return expect_failure ? 0 : 1;
    }
  }
  int expected = 0;
  int actual = 0;
  if (std::strcmp(which, "positive") == 0) {
    expected = 1;
    actual = sign(5);
  } else if (std::strcmp(which, "negative") == 0) {
    expected = -1;
    actual = sign(-5);
  } else {
    actual = sign(0);
  }
  if (actual != expected) {
    std::printf("sign(%s) = %d, expected %d\n", which, actual, expected);
    return expect_failure ? 0 : 1;
  }
  return expect_failure ? 1 : 0;
}
