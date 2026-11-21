fn main() {
    // Debug builds carry element debug info: the headless UI tests (tests/ui.rs) find elements by their
    // accessible labels, which needs it. Release builds leave it out.
    let debug = std::env::var("PROFILE").as_deref() == Ok("debug");
    let config = slint_build::CompilerConfiguration::new().with_debug_info(debug);
    slint_build::compile_with_config("ui/app.slint", config).expect("the Slint UI compiles");
}
