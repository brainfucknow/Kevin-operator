#include "CppUnitTest.h"
#include "sign.hpp"

using namespace Microsoft::VisualStudio::CppUnitTestFramework;

TEST_CLASS(SignTests) {
 public:
  TEST_METHOD(PositiveIsOne) { Assert::AreEqual(1, sign(5)); }
};
