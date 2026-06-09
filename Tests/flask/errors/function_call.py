# Semantic Error: FUNCTION_CALL_ERROR
# Expects: wrong number of arguments

def simple_func(a, b):
    return a + b

# Error: expects 2, got 1
simple_func(10)

# Error: expects 2, got 3
simple_func(1, 2, 3)

def no_args():
    return True

# Error: expects 0, got 2
no_args(True, False)

class Calculator:
    def add(self, x, y):
        return x + y

# Error: expects 3 (self, x, y), got 2
# (Note: current implementation counts all parameters including self)
calc = Calculator()
calc.add(5, 5)
