# Fuzzing [html5lib](https://github.com/html5lib/html5lib-python)

## Build
```bash
python3 main.py
```
## Usage:
```python
fuzz(is_grammar_based=True, is_mutation_based=True, fuzz_time_limit=time_limit, input_max_length=max_length)
fuzz(is_grammar_based=True, is_mutation_based=False, fuzz_time_limit=time_limit, input_max_length=max_length)
fuzz(is_grammar_based=False, is_mutation_based=False, fuzz_time_limit=time_limit, input_max_length=max_length)
# max_length set to 10000 by default, time_limit enters in the start of program
```
## Try [pythonfuzz](https://github.com/fuzzitdev/pythonfuzz)

```python
@PythonFuzz
def fuzz(buf):
    string = ""
    try:
        string = buf.decode("ascii")
        html5lib.parse(string)
    except UnicodeDecodeError:
        print(string)

fuzz()
```
