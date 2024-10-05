import html5lib
import random
import string
import time
from pythonfuzz.main import PythonFuzz

max_length = 1000

print("Enter seed: ", end="")
seed = int(input())
random.seed(seed)

print("Enter fuzzing time: ", end="")
time_limit = int(input())
start_time = time.time()

def generate_random_input(max_length: int, char_start: int, char_range: int) -> str:
    string_length = random.randrange(0, max_length + 1)
    result = ""
    for i in range(0, string_length):
        result += chr(random.randrange(char_start, char_start + char_range))
    return result

def generate_random_text(max_length: int) -> str:
    letters = string.ascii_lowercase
    return ''.join(random.choice(letters) for _ in range(random.randint(1, max_length)))

tags = ["div", "span", "p", "a", "h1", "h2", "ul", "li", "ol", "abc", ".,.,.,", "."]
attributes = ["class", "id", "style", "color", "bool", "content"]

def generate_random_html(depth: int) -> str:
    if depth == 0:
        return generate_random_text(max_length=50)

    html = "<" + random.choice(tags)
    if random.randint(0, 1):
        html += random.choice(attributes) + "=\"" + generate_random_text(max_length=50) + "\""
    html += ">"

    if random.randint(0, 1):
        html += generate_random_html(depth - 1)
    else:
        html += generate_random_text(max_length=50)
    html += "</" + random.choice(tags) + ">"

    return html

def delete_random_char(s: str) -> str:
    if s == "":
        return s
    pos = random.randint(0, len(s) - 1)
    return s[:pos] + s[pos + 1:]

def insert_random_character(s: str) -> str:
    pos = random.randint(0, len(s))
    random_character = chr(random.randrange(32, 127))
    return s[:pos] + random_character + s[pos:]

def flip_random_character(s: str) -> str:
    if s == "":
        return s
    pos_fst = random.randint(0, len(s) - 1)
    pos_snd = random.randint(0, len(s) - 1)
    lst = list(s)
    lst[pos_fst], lst[pos_snd] = lst[pos_snd], lst[pos_fst]
    return "".join(lst)

def mutate(input: str) -> str:
    mutators = [
        delete_random_char,
        insert_random_character,
        flip_random_character
    ]
    mutator = random.choice(mutators)
    return mutator(input)

def fuzz(is_grammar_based: bool,
         is_mutation_based: bool,
         fuzz_time_limit: int,
         input_max_length: int):
    print(f"Fuzzing started with seed: {seed}")
    errors_number = 0

    # Input generation -- random or grammar
    generate_input = generate_random_input
    input_arg = input_max_length  # max length of random string
    if is_grammar_based:
        generate_input = generate_random_html
        input_arg = 100  # max depth of random html

    while time.time() - start_time <= fuzz_time_limit:
        parser_input = generate_input(input_arg)
        if is_mutation_based and random.randint(1, 100) <= 50:  # mutate with 0.5 probability
            parser_input = mutate(parser_input)

        try:
            html5lib.parse(parser_input)
        except Exception as e:
            print("Error: " + e + '\n' + parser_input)
            errors_number += 1
    print("Errors found: " + str(errors_number))


fuzz(is_grammar_based=True, is_mutation_based=True, fuzz_time_limit=time_limit, input_max_length=max_length)
# fuzz(is_grammar_based=True, is_mutation_based=False, fuzz_time_limit=time_limit, input_max_length=max_length)
# fuzz(is_grammar_based=False, is_mutation_based=False, fuzz_time_limit=time_limit, input_max_length=max_length)

# @PythonFuzz
# def fuzz(buf):
#     string = ""
#     try:
#         string = buf.decode("ascii")
#         html5lib.parse(string)
#     except UnicodeDecodeError:
#         print(string)
#
# fuzz()
