#include "sign.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>

static bool matches_context(const char* expected) {
#ifdef _MSC_VER
  char* context = nullptr;
  const auto status = _dupenv_s(&context, nullptr, "KEVIN_CPP_TEST_CONTEXT");
  const bool matches = status == 0 && context && std::strcmp(context, expected) == 0;
  std::free(context);
  return matches;
#else
  const char* context = std::getenv("KEVIN_CPP_TEST_CONTEXT");
  return context && std::strcmp(context, expected) == 0;
#endif
}

static bool check_sign(const char* which) {
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
    return false;
  }
  return true;
}

int main(int argc, char** argv) {
  const char* which = argc > 1 ? argv[1] : "";
  const bool expect_failure = argc > 2 && std::strcmp(argv[2], "explicit") == 0;
  if (argc > 2) {
    const char* marker = expect_failure ? "explicit.marker" : "default.marker";
    if (!matches_context(argv[2]) || !std::ifstream(marker).is_open()) {
      std::puts("missing CTest execution context");
      return expect_failure ? 0 : 1;
    }
  }
  return check_sign(which) == expect_failure ? 1 : 0;
}
