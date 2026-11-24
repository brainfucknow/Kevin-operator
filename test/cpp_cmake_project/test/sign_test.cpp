#include "sign.hpp"

#include <cstdio>
#include <cstring>

int main(int argc, char** argv) {
  const char* which = argc > 1 ? argv[1] : "";
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
    return 1;
  }
  return 0;
}
