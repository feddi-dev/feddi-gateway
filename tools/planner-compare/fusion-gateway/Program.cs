// A plain Fusion gateway for comparing query plans with feddi: serves the archive at
// FUSION_ARCHIVE and returns the operation plan when a request sends "Fusion-Operation-Plan: 1".
var builder = WebApplication.CreateBuilder(args);

builder.Services.AddHttpClient("fusion");

builder
    .AddGraphQLGateway()
    .AddFileSystemConfiguration(Environment.GetEnvironmentVariable("FUSION_ARCHIVE") ?? "/archive/gateway.far")
    .ModifyRequestOptions(o => o.AllowOperationPlanRequests = true);

var app = builder.Build();
app.MapGraphQLHttp();
app.Run();
